package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SessionResult
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.SyncSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Everything a session needs; null from the provider while there is no group (stage 4) or no usable identity. */
class SyncContext(
    val groupId: GroupId,
    val discoveryKey: ByteArray,
    val keys: DeviceKeys,
    val store: EntryStore,
    val policy: SyncPolicy,
)

/** The radio side of a session: Android opens GATT, reads the PSM and connects the L2CAP channel. */
interface BleLink {
    /** Opens a byte stream to [address]; IOException when the peer cannot be reached. */
    suspend fun connect(address: PeerAddress): ByteStream
}

enum class SyncProblem { LINK_FAILED, REJECTED, PEER_NEEDS_UPDATE, PEER_BLOCKED }

data class SyncStatus(
    val syncing: Boolean = false,
    val finishedSessions: Int = 0,
    val lastSyncWallMs: Long? = null,
    val lastPeer: DeviceId? = null,
    val lastProblem: SyncProblem? = null,
)

/**
 * Turns scan batches into sessions (spec §8) and runs inbound ones. One session at a time: a sighting while busy waits
 * for the next batch, an inbound connection while busy is closed. Before a session (and when a peer will connect to
 * us) [prepare] collects and publishes our own entries, at most once a minute; its failure never blocks the sync.
 * [prepare] runs on the engine's own coroutine, never inside the usage pipeline lock (that Mutex is not reentrant).
 * Handshake plus session are bounded together by [sessionTimeout]; cooldown, backoff and block follow the peer the
 * Noise handshake authenticated, not the member an advert resolved to.
 * No android.* here: the radio is behind [BleLink], the scan results arrive as [Sighting]s.
 */
class SyncEngine(
    private val scope: CoroutineScope,
    private val context: suspend () -> SyncContext?,
    private val link: BleLink,
    private val prepare: suspend () -> Unit,
    private val monoClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val policy: ConnectPolicy = ConnectPolicy(),
    private val log: (String) -> Unit = {},
    private val sessionTimeout: Duration = SESSION_TIMEOUT,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _advert = MutableStateFlow<AdvertPayload?>(null)

    /** What we should advertise now; changes with the tag window and with our vector. */
    val advert: StateFlow<AdvertPayload?> = _advert.asStateFlow()

    private val gate = Mutex()
    private val prepareLock = Mutex()
    private var lastPrepareMono: Long? = null

    @Volatile
    private var fastUntilMono = Long.MIN_VALUE

    private val sightings = Channel<List<Sighting>>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (batch in sightings) {
                try {
                    handle(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("sightings failed: $e")
                }
            }
        }
    }

    /** From the scan receiver thread; never blocks (under load newer batches replace older ones). */
    fun onSightings(batch: List<Sighting>) {
        sightings.trySend(batch)
    }

    /** Fast mode (spec §8 «Быстрый режим»): until [untilMono] the cooldown is skipped (a block is not). */
    fun setFast(untilMono: Long) {
        fastUntilMono = untilMono
    }

    /** An accepted L2CAP connection: the responder side if no other session runs, else it is closed at once. */
    fun onInbound(stream: ByteStream) {
        if (!gate.tryLock()) {
            log("inbound while another session runs: closed")
            stream.close()
            return
        }
        scope.launch {
            val ctx = contextOrNull()
            if (ctx == null) {
                stream.close()
                return@launch
            }
            record(null, runSession(ctx, stream, initiator = false))
        }.invokeOnCompletion { cause ->
            if (cause != null) stream.close()
            gate.unlock()
        }
    }

    suspend fun refreshAdvert() {
        _advert.value = contextOrNull()?.let { advertFor(it) }
    }

    private suspend fun advertFor(ctx: SyncContext): AdvertPayload =
        AdvertPayload.build(ctx.discoveryKey, ctx.keys.deviceId, ctx.store.versionVector().shortHash(), wallClock())

    private suspend fun handle(batch: List<Sighting>) {
        val ctx = contextOrNull() ?: return
        val self = advertFor(ctx).also { _advert.value = it }
        val members = Membership.compute(ctx.groupId, ctx.store.membershipEntries())?.active?.keys ?: return
        val resolver = PeerResolver(ctx.discoveryKey, members)
        val nowWall = wallClock()
        val nowMono = monoClock()
        val fast = nowMono < fastUntilMono
        // One decision per peer per batch: the strongest sighting wins (a batch holds many repeats of each advert).
        val best = LinkedHashMap<PeerKey, Pair<Sighting, DeviceId?>>()
        for (s in batch) {
            val id = resolver.resolve(s.payload.deviceTag, nowWall)
            val key = id?.let(PeerKey::of) ?: PeerKey.ofTag(s.payload.deviceTag)
            val seen = best[key]
            if (seen == null || s.rssi > seen.first.rssi) best[key] = s to id
        }
        for ((key, pair) in best) {
            val (s, id) = pair
            when (val d = policy.decide(s, key, id, self, ctx.keys.deviceId, ctx.discoveryKey, nowWall, nowMono, fast)) {
                is Decision.Ignore -> log("skip ${key.value.take(12)}: ${d.reason}")
                is Decision.Prepare -> prepareIfDue()
                is Decision.Connect -> initiate(ctx, d)
            }
        }
    }

    private fun initiate(ctx: SyncContext, d: Decision.Connect) {
        if (!gate.tryLock()) return // a session runs; the next batch tries again
        scope.launch {
            prepareIfDue()
            val stream = try {
                withTimeout(CONNECT_TIMEOUT) { link.connect(d.address) }
            } catch (e: TimeoutCancellationException) {
                log("connect to ${d.peer.value.take(12)} timed out")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("connect to ${d.peer.value.take(12)} failed: $e")
                null
            }
            val outcome = if (stream == null) {
                Outcome(SessionResult.Failed(IOException("connect failed")), authenticated = null)
            } else {
                runSession(ctx, stream, initiator = true)
            }
            record(d.peer, outcome)
        }.invokeOnCompletion { gate.unlock() }
    }

    /** A session's result and the member its Noise static key proved, null when the handshake never finished. */
    private class Outcome(val result: SessionResult, val authenticated: DeviceId?)

    /**
     * Handshake plus [SyncSession], bounded as a whole by [sessionTimeout] (spec: a session lasts at most 5 minutes):
     * a peer that keeps sending messages that are not Done resets the idle timeout but cannot hold the gate past it.
     * The stream is always closed when this returns.
     */
    private suspend fun runSession(ctx: SyncContext, stream: ByteStream, initiator: Boolean): Outcome {
        _status.update { it.copy(syncing = true) }
        val prologue = SecureChannel.prologue(ctx.groupId)
        var authenticated: DeviceId? = null
        val result = try {
            withTimeout(sessionTimeout) {
                val channel = if (initiator) {
                    SecureChannel.initiate(stream, ctx.keys.dh, prologue)
                } else {
                    SecureChannel.accept(stream, ctx.keys.dh, prologue)
                }
                val peerId = Membership.compute(ctx.groupId, ctx.store.membershipEntries())
                    ?.activeMemberByDhKey(channel.remoteStaticKey)?.id
                authenticated = peerId
                if (peerId != null && policy.isBlocked(PeerKey.of(peerId), monoClock())) {
                    channel.close()
                    SessionResult.Rejected(Reject.Reason.TOO_MANY_INVALID, byPeer = false)
                } else {
                    SyncSession(ctx.store, ctx.policy, log = log).run(channel)
                }
            }
        } catch (e: TimeoutCancellationException) {
            log("session timed out after $sessionTimeout")
            SessionResult.Failed(IOException("session timed out after $sessionTimeout", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionResult.Failed(e)
        } finally {
            stream.close()
        }
        return Outcome(result, authenticated)
    }

    /**
     * Cooldown, backoff and block are keyed by the peer the Noise handshake proved, never by the advert alone: a 2-byte
     * device-tag collision can resolve an advert to the wrong member. When the advert key ([initiatedTo]) differs from
     * the proven one (or nothing was proven), it gets only a retry delay so the same address is not redialled at once,
     * never a block, which belongs to the proven peer.
     */
    private suspend fun record(initiatedTo: PeerKey?, outcome: Outcome) {
        val result = outcome.result
        val now = monoClock()
        val proven = when (result) {
            is SessionResult.Completed -> result.peer
            is SessionResult.TooManyInvalid -> result.peer
            else -> outcome.authenticated
        }?.let(PeerKey::of)
        if (initiatedTo != null && proven != null && proven != initiatedTo) {
            log("advert of ${initiatedTo.value.take(12)} led to ${proven.value.take(12)}: outcome kept for the latter")
        }
        val primary = proven ?: initiatedTo
        val secondary = initiatedTo?.takeIf { it != primary }
        val problem = when (result) {
            is SessionResult.Completed -> {
                listOfNotNull(primary, secondary).forEach { policy.onCompleted(it, now) }
                null
            }
            is SessionResult.TooManyInvalid -> {
                primary?.let { policy.onBlocked(it, now) }
                secondary?.let { policy.onCompleted(it, now) }
                SyncProblem.PEER_BLOCKED
            }
            is SessionResult.Rejected -> {
                listOfNotNull(primary, secondary).forEach { policy.onCompleted(it, now) }
                when {
                    result.reason == Reject.Reason.PROTOCOL_VERSION -> SyncProblem.PEER_NEEDS_UPDATE
                    result.reason == Reject.Reason.TOO_MANY_INVALID && !result.byPeer -> SyncProblem.PEER_BLOCKED
                    else -> SyncProblem.REJECTED
                }
            }
            is SessionResult.Failed -> {
                listOfNotNull(primary, secondary).forEach { policy.onFailed(it, now) }
                SyncProblem.LINK_FAILED
            }
        }
        log("session ${if (initiatedTo == null) "inbound" else primary!!.value.take(12)}: $result")
        _status.update { s ->
            s.copy(
                syncing = false,
                finishedSessions = s.finishedSessions + 1,
                lastSyncWallMs = if (result is SessionResult.Completed) wallClock() else s.lastSyncWallMs,
                lastPeer = (result as? SessionResult.Completed)?.peer ?: s.lastPeer,
                lastProblem = problem,
            )
        }
        refreshAdvert() // the vector changed: peers should see the new hash
    }

    private suspend fun prepareIfDue() = prepareLock.withLock {
        val now = monoClock()
        val last = lastPrepareMono
        if (last != null && now - last in 0 until PREPARE_MIN_INTERVAL_MS) return@withLock
        lastPrepareMono = now
        try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("prepare failed: $e")
        }
    }

    private suspend fun contextOrNull(): SyncContext? = try {
        context()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("no sync context: $e")
        null
    }

    companion object {
        val CONNECT_TIMEOUT = 30.seconds
        const val PREPARE_MIN_INTERVAL_MS = 60_000L
        val SESSION_TIMEOUT = 5.minutes
    }
}
