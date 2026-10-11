package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.WireConstants
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.sync.ByteStream
import app.kinsight.core.sync.pairing.GuestResult
import app.kinsight.core.sync.pairing.HostDecision
import app.kinsight.core.sync.pairing.HostOutcome
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.sync.pairing.Joiner
import app.kinsight.core.sync.pairing.PairingException
import app.kinsight.core.sync.pairing.PairingGuest
import app.kinsight.core.sync.pairing.PairingHost
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.InviteHostNotFoundException
import app.kinsight.core.transport.ble.PairingHostHandle
import app.kinsight.core.transport.ble.PairingLink
import app.kinsight.core.transport.ble.PairingRadioException
import app.kinsight.core.transport.ble.RadioProblem
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsagePipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Why pairing failed, for the screens. A guest whose token does not match fails inside the Noise handshake (the token
 * is mixed into the prologue), which surfaces as [BAD_TOKEN] («this QR code is no longer valid»). A blank or unusable
 * admin name and an invite of another format or protocol version are [INVALID].
 */
enum class PairFailure { NO_PERMISSION, BLUETOOTH_OFF, RADIO, NOT_FOUND, LINK, WRONG_ADMIN, BAD_TOKEN, EXPIRED, DECLINED, REMOVED_BEFORE, INVALID, NOT_ADMIN, ALREADY_IN_GROUP, IDENTITY }

sealed interface InviteState {
    data object Starting : InviteState

    /** [problem]: a standing radio problem while hosting (PairingHostHandle.problem); the QR stays usable. */
    data class Showing(val uri: String, val expiresAtMs: Long, val problem: RadioProblem? = null) : InviteState

    /** [code]: the channel's SAS (PairingSas) the guest's screen shows; the admin adds only when they match. */
    data class Confirm(val uri: String, val expiresAtMs: Long, val name: String, val role: Role, val code: String = "") : InviteState

    data class Added(val name: String) : InviteState

    data object Expired : InviteState

    data class Failed(val reason: PairFailure) : InviteState
}

sealed interface JoinState {
    data object Searching : JoinState

    data object Connecting : JoinState

    /** [code]: this channel's SAS, the same digits the admin's confirmation shows. */
    data class Waiting(val adminName: String, val code: String = "") : JoinState

    data object Saving : JoinState

    data object Joined : JoinState

    data class Failed(val reason: PairFailure) : JoinState
}

sealed interface Precheck {
    data class Reject(val reason: PairReject.Reason) : Precheck

    /** Already an active member with these keys and this role: the welcome was lost, send it again, no new op. */
    data object Rewelcome : Precheck

    data object AskAdmin : Precheck
}

internal object InviteRules {
    /** [bound]: the first joiner the admin accepted on this invite; the token belongs to it from then on. */
    fun precheck(bound: DeviceId?, joiner: Joiner, membership: MembershipState, role: DeviceRole): Precheck {
        if (bound != null && bound != joiner.id) return Precheck.Reject(PairReject.Reason.BAD_TOKEN)
        if (joiner.id in membership.departed) return Precheck.Reject(PairReject.Reason.ALREADY_REMOVED)
        val existing = membership.active[joiner.id] ?: return Precheck.AskAdmin
        val same = existing.dhPublicKey.toByteArray().contentEquals(joiner.dhPublicKey) && existing.role == role
        return if (same) Precheck.Rewelcome else Precheck.Reject(PairReject.Reason.REASON_UNSPECIFIED)
    }
}

/**
 * Pairing for the screens (spec §6.4): an admin's invite session (QR, radio, confirmation, AddMember) and a new
 * device's join session (dial, handshake, wait, store the welcome in a freshly wiped database, consent, fast sync).
 *
 * Locks: the coordinator holds none of the app's locks itself. A session's own monitor guards its state and is never
 * held across a suspension. LocalReset.forgetGroup (controlLock → stateLock → pipeline lock) is called outside any
 * pipeline.exclusive; AddMember goes through pipeline.appendOwn. GroupStore file I/O runs on [io].
 */
class PairingCoordinator(
    private val link: PairingLink,
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val pipeline: UsagePipeline,
    private val settings: SettingsStore,
    private val reset: LocalReset,
    private val sync: SyncControl,
    private val snapshots: Snapshots,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dialTimeout: Duration = 60.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _currentInvite = MutableStateFlow<String?>(null)

    /** The URI of the open invite, null when none is open (debug automation reads it). */
    val currentInviteUri: StateFlow<String?> = _currentInvite.asStateFlow()

    fun invite(scope: CoroutineScope, role: Role, adminName: String): InviteSession = InviteSession(scope, role, adminName)

    /**
     * [freshIdentity]: «Отключить и подключить заново» after [PairFailure.REMOVED_BEFORE]: the identity is reset
     * (LocalReset.resetIdentity, the device name stays) before the handshake, so the admin adds new keys.
     */
    fun join(scope: CoroutineScope, invite: Invite, deviceName: String, consent: Boolean, freshIdentity: Boolean = false): JoinSession =
        JoinSession(scope, invite, deviceName, consent, freshIdentity)

    /** One join stores its welcome at a time; the second finds the first one's group and refuses. */
    private val commitLock = Mutex()

    /**
     * For app start, next to UsagePipeline.reconcileSharing: a join (or the consent screen) records the child's consent
     * before the group file and turns sharing on after it; a crash in between leaves a consenting CHILD with sharing
     * off and no own SHARING_* signed. That state has no other cause (a child who pauses signs SHARING_PAUSED; sharing
     * that was never on cannot be turned off), so sharing is turned on as the child agreed. Parents, devices without
     * consent and devices without a group are left alone.
     */
    suspend fun finishInterruptedJoin() {
        val group = withContext(io) { groups.read() } ?: return
        val s = settings.current()
        if (s.consentAtMs == null || s.sharing) return
        val self = keys().deviceId
        val store = RoomEntryStore(db, group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return
        if (membership.active[self]?.isChild != true) return
        if (store.stateEvents().any { it.author == self && it.body.state_event?.kind in WireConstants.SHARING_KINDS }) return
        Log.i(TAG, "finishing an interrupted join: sharing on as consented")
        pipeline.setSharing(true)
    }

    /**
     * One open invite. Guests are served one at a time (a second one while the first is being handled is hung up on
     * and may retry). The token belongs to the first joiner the admin accepts (the host also binds its X25519 key);
     * an active member with the same keys and role is welcomed again without a new AddMember, so an AddMember whose
     * welcome was refused or lost (expiry re-check, dropped link) is repaired by the next attempt. Terminal states
     * (Added, Expired, Failed) never change, except that a welcome racing the expiry still reports Added.
     */
    inner class InviteSession internal constructor(
        parent: CoroutineScope,
        private val role: Role,
        private val adminName: String,
    ) {
        private val _state = MutableStateFlow<InviteState>(InviteState.Starting)
        val state: StateFlow<InviteState> = _state.asStateFlow()

        private val lock = Any()
        private val sessionJob = SupervisorJob(parent.coroutineContext[Job])
        private val scope = CoroutineScope(parent.coroutineContext + sessionJob)
        private val busy = AtomicBoolean(false)

        // Guarded by lock.
        private var ended = false
        private var handle: PairingHostHandle? = null
        private var opened: Opened? = null
        private var decision: CompletableDeferred<Boolean>? = null
        private var problems: Job? = null

        /** Only touched by the guest being served (one at a time). */
        @Volatile private var bound: DeviceId? = null

        init {
            scope.launch {
                try {
                    open()
                } finally {
                    shutdown() // also when the screen's scope ends without close(): the radio and the URI go
                }
            }
        }

        fun confirm() {
            synchronized(lock) { decision }?.complete(true)
        }

        fun decline() {
            synchronized(lock) { decision }?.complete(false)
        }

        /** Stops the radio and refuses a guest waiting for the admin; the state stays as it was. */
        fun close() {
            shutdown()
            sessionJob.cancel()
        }

        private suspend fun open() {
            val k: DeviceKeys
            val group: GroupConfig
            val invite: Invite
            try {
                k = keys()
                group = withContext(io) { groups.read() } ?: return fail(PairFailure.NOT_ADMIN)
                val membership = Membership.compute(group.groupId, RoomEntryStore(db, group.groupId).membershipEntries())
                if (membership?.active?.get(k.deviceId)?.isAdmin != true) return fail(PairFailure.NOT_ADMIN)
                invite = Invite.create(group.groupId, k, role.toDeviceRole(), adminName) // IllegalArgumentException: bad name
            } catch (e: CancellationException) {
                throw e
            } catch (e: KeyStorageException) {
                return fail(PairFailure.IDENTITY)
            } catch (e: Exception) {
                return fail(PairFailure.INVALID) // a blank or unsafe admin name, an unreadable group file or database
            }
            val expiresAt = clock() + Invite.LIFETIME_MS
            val uri = invite.toUri()
            val host = PairingHost(invite, k.dh, expiresAt, clock)
            val h = try {
                link.host(scope, invite.advertTag) { stream -> onInbound(stream, host, group) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingRadioException) {
                return fail(e.reason.toFailure())
            } catch (e: Exception) {
                return fail(PairFailure.RADIO)
            }
            synchronized(lock) {
                if (ended) {
                    h.close()
                    return
                }
                handle = h
                opened = Opened(uri, expiresAt)
                _currentInvite.value = uri
                move(InviteState.Showing(uri, expiresAt, h.problem.value))
                problems = scope.launch {
                    h.problem.collect { p ->
                        synchronized(lock) {
                            val cur = _state.value
                            if (cur is InviteState.Showing && cur.problem != p) _state.value = cur.copy(problem = p)
                        }
                    }
                }
            }
            // The screen's timer runs on elapsed time; the token's expiry (PairingHost, decide) on [clock], so a
            // wall-clock change cannot close the radio early.
            delay(Invite.LIFETIME_MS)
            synchronized(lock) { move(InviteState.Expired) }
            shutdown() // a guest still waiting for the admin is refused EXPIRED
            sessionJob.complete() // that refusal may still finish; nothing new starts
        }

        /** Called on the link's accept thread: never blocks. */
        private fun onInbound(stream: ByteStream, host: PairingHost, group: GroupConfig) {
            if (synchronized(lock) { ended } || !busy.compareAndSet(false, true)) {
                stream.close()
                return
            }
            scope.launch {
                try {
                    when (val outcome = host.serve(stream) { joiner -> decide(joiner, group) }) {
                        is HostOutcome.Welcomed -> {
                            // Everything is done before Added is published.
                            shutdown()
                            try {
                                snapshots.invalidate()
                                sync.start(fast = true)
                            } catch (e: Exception) {
                                Log.w(TAG, "after adding ${outcome.joiner.id}: $e") // the AddMember is stored: still Added
                            }
                            synchronized(lock) { move(InviteState.Added(outcome.joiner.displayName)) }
                            sessionJob.cancel() // ends open()'s timer; this coroutine is the last one
                        }
                        is HostOutcome.Rejected -> backToShowing()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backToShowing() // the link dropped or the guest never asked
                }
            }.invokeOnCompletion {
                // Also when the session ended before the body ran: serve never closed the stream then.
                runCatching { stream.close() }
                busy.set(false)
            }
        }

        private suspend fun decide(joiner: Joiner, group: GroupConfig): HostDecision {
            val store = RoomEntryStore(db, group.groupId)
            val before = Membership.compute(group.groupId, store.membershipEntries())
                ?: return HostDecision.Reject(PairReject.Reason.REASON_UNSPECIFIED)
            when (val p = InviteRules.precheck(bound, joiner, before, role.toDeviceRole())) {
                is Precheck.Reject -> return HostDecision.Reject(p.reason)
                Precheck.Rewelcome -> {
                    bound = joiner.id
                    return welcome(before, group)
                }
                Precheck.AskAdmin -> Unit
            }
            val d = CompletableDeferred<Boolean>()
            val o: Opened
            synchronized(lock) {
                o = opened ?: return HostDecision.Reject(PairReject.Reason.REASON_UNSPECIFIED)
                if (ended) return HostDecision.Reject(refusal(o))
                decision = d
                move(InviteState.Confirm(o.uri, o.expiresAtMs, joiner.displayName, role, joiner.sas))
            }
            val accepted = try {
                d.await()
            } finally {
                synchronized(lock) { if (decision === d) decision = null }
            }
            if (!accepted) {
                backToShowing()
                return HostDecision.Reject(refusal(o))
            }
            bound = joiner.id
            pipeline.appendOwn { author ->
                listOf(author.addMember(joiner.signPublicKey, joiner.dhPublicKey, joiner.displayName, role.toDeviceRole()))
            }
            // The host may still refuse (expiry re-check): the member stays added and the next attempt is re-welcomed.
            return welcome(Membership.compute(group.groupId, store.membershipEntries())!!, group)
        }

        private fun refusal(o: Opened): PairReject.Reason =
            if (clock() > o.expiresAtMs) PairReject.Reason.EXPIRED else PairReject.Reason.DECLINED

        private fun welcome(state: MembershipState, group: GroupConfig) =
            HostDecision.Welcome(group.discoveryKey, state.effectiveEntries.map { it.bytes })

        /** Must hold [lock]. Terminal states stay, except Added over Expired (a welcome racing the expiry). */
        private fun move(next: InviteState): Boolean {
            val cur = _state.value
            val terminal = cur is InviteState.Added || cur is InviteState.Failed ||
                (cur is InviteState.Expired && next !is InviteState.Added)
            if (terminal) return false
            _state.value = next
            return true
        }

        private fun backToShowing() = synchronized(lock) {
            val o = opened
            val cur = _state.value
            if (o != null && !ended && (cur is InviteState.Confirm || cur is InviteState.Showing)) {
                move(InviteState.Showing(o.uri, o.expiresAtMs, handle?.problem?.value))
            }
        }

        /** Only from open(), which ends right after: the session's job completes with it. */
        private fun fail(reason: PairFailure) {
            synchronized(lock) { move(InviteState.Failed(reason)) }
            shutdown()
            sessionJob.complete()
        }

        private fun shutdown() {
            val toClose: PairingHostHandle?
            synchronized(lock) {
                ended = true
                toClose = handle
                handle = null
                problems?.cancel()
                opened?.let { _currentInvite.compareAndSet(it.uri, null) }
                decision?.complete(false)
            }
            runCatching { toClose?.close() }
        }
    }

    inner class JoinSession internal constructor(
        scope: CoroutineScope,
        private val invite: Invite,
        private val deviceName: String,
        private val consent: Boolean,
        private val freshIdentity: Boolean = false,
    ) {
        private val _state = MutableStateFlow<JoinState>(JoinState.Searching)
        val state: StateFlow<JoinState> = _state.asStateFlow()
        private val job: Job = scope.launch { run() }

        /** Stops searching or waiting; once the welcome is being stored, the store completes. */
        fun cancel() = job.cancel()

        private suspend fun run() {
            try {
                // A cut-short identity reset finishes before the handshake: the keys it replaces must not be the ones
                // the admin adds (and old keys over an emptied log would reuse seqs, audit A-4).
                try {
                    if (freshIdentity) {
                        // Never wipes a group: a device in one has nothing to retry (and is refused below as before).
                        if (withContext(io) { groups.exists() }) return fail(PairFailure.ALREADY_IN_GROUP)
                        // Not cancellable: a Cancel (or leaving the screen) mid-way would cut the reset short and
                        // leave a blocking RESET_FAILED; it is short, and the cancel takes effect at the dial.
                        withContext(NonCancellable) { reset.resetIdentity(keepName = true) }
                    } else {
                        reset.finishInterruptedReset()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return fail(PairFailure.IDENTITY)
                }
                if (withContext(io) { groups.exists() }) return fail(PairFailure.ALREADY_IN_GROUP)
                val k = keys()
                val stream = link.dial(invite.advertTag, dialTimeout)
                _state.value = JoinState.Connecting
                val result = PairingGuest(invite, k, deviceName).join(stream) { sas -> _state.value = JoinState.Waiting(invite.adminName, sas) }
                when (result) {
                    is GuestResult.Rejected -> fail(result.reason.toFailure())
                    is GuestResult.Joined -> {
                        _state.value = JoinState.Saving
                        // The admin has added us: a half-stored welcome would be worse than finishing.
                        _state.value = withContext(NonCancellable) { save(k, result) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingRadioException) {
                fail(e.reason.toFailure())
            } catch (e: InviteHostNotFoundException) {
                fail(PairFailure.NOT_FOUND)
            } catch (e: PairingException) {
                fail(if (e.kind == PairingException.Kind.WRONG_ADMIN) PairFailure.WRONG_ADMIN else PairFailure.INVALID)
            } catch (e: CryptoException) {
                fail(PairFailure.BAD_TOKEN) // the handshake failed: the token in the prologue is not the host's
            } catch (e: KeyStorageException) {
                fail(PairFailure.IDENTITY)
            } catch (e: Exception) {
                fail(PairFailure.LINK)
            }
        }

        /** [commit], and a failure after which the group file is in place still reads as joined (it is). */
        private suspend fun save(k: DeviceKeys, r: GuestResult.Joined): JoinState = try {
            commit(k, r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val stored = runCatching { withContext(io) { groups.read() }?.groupId == invite.groupId }.getOrDefault(false)
            if (!stored) throw e
            Log.w(TAG, "joined, but finishing failed: $e")
            JoinState.Joined
        }

        /**
         * Under [commitLock], refusing when a group appeared meanwhile (another join, or «create group»): a fresh database
         * (no stale cursor, markers or entries), the membership, the child's consent (before the group
         * file, so [finishInterruptedJoin] can finish it after a crash), the group file, then sharing and a fast sync.
         * Steps after the group file are best effort: the device is in the group whatever they do.
         */
        private suspend fun commit(k: DeviceKeys, r: GuestResult.Joined): JoinState = commitLock.withLock {
            // The handshake keys must still be the device's keys and no identity reset may be pending (it ran before
            // the handshake, see run): otherwise the admin added keys this device no longer holds.
            if (reset.resetPending() || keys().deviceId != k.deviceId) return@withLock JoinState.Failed(PairFailure.IDENTITY)
            if (withContext(io) { groups.exists() }) return@withLock JoinState.Failed(PairFailure.ALREADY_IN_GROUP)
            reset.forgetGroup()
            RoomEntryStore(db, invite.groupId).applyBatch(r.membership)
            val share = consent && invite.role == DeviceRole.CHILD
            if (share) settings.recordConsent(clock())
            withContext(io) { groups.write(GroupConfig(invite.groupId, r.discoveryKey)) }
            try {
                if (share) pipeline.setSharing(true) else pipeline.reconcileSharing()
            } catch (e: Exception) {
                Log.w(TAG, "joined, sharing not settled: $e") // finishInterruptedJoin / reconcileSharing at the next start
            }
            try {
                snapshots.invalidate()
                sync.start(fast = true)
            } catch (e: Exception) {
                Log.w(TAG, "joined, no fast sync: $e")
            }
            JoinState.Joined
        }

        private fun fail(reason: PairFailure) {
            _state.value = JoinState.Failed(reason)
        }
    }
}

private const val TAG = "KinsightPairing"

/** An invite on the air: its QR text and when its token stops being accepted. */
private class Opened(val uri: String, val expiresAtMs: Long)

private fun PairingRadioException.Reason.toFailure(): PairFailure = when (this) {
    PairingRadioException.Reason.NO_PERMISSION -> PairFailure.NO_PERMISSION
    PairingRadioException.Reason.BLUETOOTH_OFF -> PairFailure.BLUETOOTH_OFF
    PairingRadioException.Reason.RADIO -> PairFailure.RADIO
}

private fun PairReject.Reason.toFailure(): PairFailure = when (this) {
    PairReject.Reason.BAD_TOKEN -> PairFailure.BAD_TOKEN
    PairReject.Reason.EXPIRED -> PairFailure.EXPIRED
    PairReject.Reason.DECLINED -> PairFailure.DECLINED
    PairReject.Reason.ALREADY_REMOVED -> PairFailure.REMOVED_BEFORE
    PairReject.Reason.BAD_PROOF, PairReject.Reason.NAME_INVALID, PairReject.Reason.REASON_UNSPECIFIED -> PairFailure.INVALID
}
