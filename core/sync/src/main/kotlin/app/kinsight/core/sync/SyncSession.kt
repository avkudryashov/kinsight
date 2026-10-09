package app.kinsight.core.sync

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.Done
import app.kinsight.core.model.proto.EntryBatch
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.VectorAnnounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.ProtocolException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val PROTOCOL_VERSION = 1

sealed interface SessionResult {
    data class Completed(val peer: DeviceId, val received: Int, val sentBatches: Int) : SessionResult
    data class Rejected(val reason: Reject.Reason, val byPeer: Boolean) : SessionResult
    data class TooManyInvalid(val peer: DeviceId) : SessionResult
    data class Failed(val cause: Throwable) : SessionResult
}

/** One symmetric sync session over an established [SecureChannel]. Both sides run the same code. */
class SyncSession(
    private val store: EntryStore,
    private val policy: SyncPolicy,
    private val protocolVersion: Int = PROTOCOL_VERSION,
    private val idleTimeout: Duration = 30.seconds,
    private val sessionTimeout: Duration = 5.minutes,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(channel: SecureChannel): SessionResult = try {
        withTimeout(sessionTimeout) { exchange(channel) }
    } catch (e: TimeoutCancellationException) {
        SessionResult.Failed(e)
    } catch (e: IOException) {
        SessionResult.Failed(e)
    } catch (e: CryptoException) {
        SessionResult.Failed(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("session failed: $e")
        SessionResult.Failed(e)
    } finally {
        channel.close()
    }

    private suspend fun send(ch: SecureChannel, msg: SyncMessage) = ch.send(SyncMessage.ADAPTER.encode(msg))

    private suspend fun receive(ch: SecureChannel): SyncMessage {
        val bytes = withTimeout(idleTimeout) { ch.receive() }
        return try {
            SyncMessage.ADAPTER.decode(bytes)
        } catch (e: Exception) {
            throw ProtocolException("undecodable message")
        }
    }

    /**
     * Best-effort notice: the local decision stands even if the peer has already hung up
     * (e.g. both sides rejected each other at the same moment).
     */
    private suspend fun reject(ch: SecureChannel, reason: Reject.Reason) {
        try {
            send(ch, SyncMessage(reject = Reject(reason = reason)))
        } catch (e: IOException) {
            log("could not deliver reject $reason: ${e.message}")
        } catch (e: CryptoException) {
            log("could not deliver reject $reason: ${e.message}")
        }
    }

    private suspend fun exchange(ch: SecureChannel): SessionResult = coroutineScope {
        val groupBytes = store.groupId.bytes().toByteString()
        val ourMembership = store.membershipEntries()
        val ourView = Membership.compute(store.groupId, ourMembership) ?: throw ProtocolException("no valid genesis")
        // Hash and offer cover only the effective entries: ops that change nothing (e.g. from non-admins) stay
        // in the author log flow and cannot bloat or perturb the membership exchange.
        val ourEffective = ourView.effectiveEntries.sortedWith(compareBy({ it.author.hex }, { it.seq }))
        val ourHash = Membership.hash(store.groupId, ourEffective).toByteString()
        // Hello goes out before the peer is authenticated as a member: it reveals no vector and no membership.
        send(
            ch,
            SyncMessage(
                hello = Hello(
                    protocol_version = protocolVersion,
                    group_id = groupBytes,
                    membership_hash = ourHash,
                    wants_foreign_entries = policy.showOthers,
                ),
            ),
        )
        val first = receive(ch)
        first.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
        val hello = first.hello ?: throw ProtocolException("expected hello")
        if (hello.protocol_version != protocolVersion) {
            reject(ch, Reject.Reason.PROTOCOL_VERSION)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false)
        }
        // Defensive only: groupId is bound into the Noise prologue, so peers of another group already
        // fail the handshake. Kept in case a transport ever authenticates the channel differently.
        if (hello.group_id != groupBytes) {
            reject(ch, Reject.Reason.WRONG_GROUP)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.WRONG_GROUP, byPeer = false)
        }

        var known = ourMembership
        if (hello.membership_hash != ourHash) {
            // Exactly one offer each way, empty unless the peer is an active member in our current view, so a
            // stranger (or a removed device) never learns the membership log and neither side waits forever.
            val shared = if (ourView.activeMemberByDhKey(ch.remoteStaticKey) != null) ourEffective else emptyList()
            val offered = mutableListOf<DecodedEntry>()
            var peerReject: Reject.Reason? = null
            coroutineScope {
                // Pages go out while we read the peer's, so two large offers cannot block on a bounded transport.
                val pages = launch { sendOffer(ch, shared) }
                while (true) {
                    val m = receive(ch)
                    m.reject?.let {
                        peerReject = it.reason
                        pages.cancel()
                        return@coroutineScope
                    }
                    val page = m.membership ?: throw ProtocolException("expected membership offer")
                    page.entries.mapNotNullTo(offered) { raw ->
                        decodeOrNull(raw)?.takeIf { MembershipLimits.withinLimits(it) }
                    }
                    if (!page.more) break
                }
            }
            peerReject?.let { return@coroutineScope SessionResult.Rejected(it, byPeer = true) }
            known = ourMembership + offered
        }
        val membership = Membership.compute(store.groupId, known) ?: throw ProtocolException("no valid genesis")
        store.storeMembership(membership.effectiveEntries.toList())

        val peer = membership.activeMemberByDhKey(ch.remoteStaticKey)
        if (peer == null || policy.self !in membership.active) {
            reject(ch, Reject.Reason.NOT_A_MEMBER)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.NOT_A_MEMBER, byPeer = false)
        }

        // Authenticated both ways: only now exchange vectors.
        send(ch, SyncMessage(vector_announce = VectorAnnounce(vector = store.versionVector().toProto())))
        val announceMsg = receive(ch)
        announceMsg.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
        val announce = announceMsg.vector_announce ?: throw ProtocolException("expected vector announce")
        val peerVector = VersionVector.fromProto(announce.vector)

        val validator = EntryValidator(store.groupId, membership, policy)
        val sentTotal = CompletableDeferred<Int>()
        val sender = launch {
            try {
                val n = sendData(ch, membership, peerVector, hello.wants_foreign_entries, peer.id)
                send(ch, SyncMessage(done = Done()))
                sentTotal.complete(n)
            } catch (e: IOException) {
                // Our direction is gone. Do not cancel the session: batches the peer already delivered are still
                // applied; the reader then hits the closed link (or the idle timeout) and the session fails.
                log("send failed: ${e.message}")
            }
        }

        // Dedicated reader: keeps draining the link while our sender is still pushing, so the peer's
        // acks and batches never back up behind us on a bounded transport.
        val inbox = Channel<SyncMessage>(Channel.UNLIMITED)
        val reader = launch {
            try {
                while (true) inbox.send(receive(ch))
            } catch (e: Throwable) {
                inbox.close(e)
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            }
        }

        try {
            var invalid = 0
            var received = 0
            var acks = 0
            var peerDone = false
            var sentBatches = -1 // -1 until our sender has finished
            while (!(peerDone && sentBatches >= 0 && acks >= sentBatches)) {
                val msg = select<SyncMessage?> {
                    if (sentBatches < 0) sentTotal.onAwait { sentBatches = it; null }
                    inbox.onReceive { it }
                } ?: continue
                when {
                    msg.batch != null -> {
                        val inBatch = HashSet<Pair<DeviceId, Long>>()
                        val accepted = mutableListOf<DecodedEntry>()
                        val seen = mutableMapOf<DeviceId, MutableSet<Long>>()
                        for (raw in msg.batch!!.entries) {
                            val e = decodeOrNull(raw)
                            val verdict = if (e == null) {
                                Verdict.Invalid("undecodable")
                            } else {
                                val key = e.author to e.seq
                                validator.check(e, alreadyAccounted = key in inBatch || store.isAccounted(e.author, e.seq))
                            }
                            when (verdict) {
                                Verdict.Accept -> {
                                    accepted += e!!
                                    inBatch += e.author to e.seq
                                }
                                Verdict.Expired -> {
                                    seen.getOrPut(e!!.author) { mutableSetOf() } += e.seq
                                    inBatch += e.author to e.seq
                                }
                                Verdict.Duplicate, Verdict.Unwanted -> Unit
                                is Verdict.Invalid -> {
                                    invalid++
                                    log("invalid entry from ${peer.id.hex}: ${verdict.reason}")
                                }
                            }
                        }
                        if (invalid > MAX_INVALID) {
                            sender.cancel()
                            reject(ch, Reject.Reason.TOO_MANY_INVALID)
                            return@coroutineScope SessionResult.TooManyInvalid(peer.id)
                        }
                        store.applyBatch(accepted, seen)
                        received += accepted.size
                        send(ch, SyncMessage(ack = BatchAck(vector = store.versionVector().toProto())))
                    }
                    msg.ack != null -> acks++
                    msg.done != null -> {
                        peerDone = true
                        // Author completion: the peer has now sent every entry of its own log above our announced
                        // contig (all its batches precede Done), so its own announced seq closes any gap we have.
                        // Only when we asked for foreign entries; otherwise it was not obliged to send any.
                        if (policy.showOthers) store.completeAuthor(peer.id, peerVector[peer.id])
                    }
                    msg.reject != null -> {
                        sender.cancel()
                        return@coroutineScope SessionResult.Rejected(msg.reject!!.reason, byPeer = true)
                    }
                    else -> throw ProtocolException("unexpected message")
                }
            }
            SessionResult.Completed(peer.id, received, sentBatches)
        } finally {
            reader.cancel()
            sender.cancel()
        }
    }

    private suspend fun sendData(
        ch: SecureChannel,
        membership: MembershipState,
        peerVector: VersionVector,
        peerWantsForeign: Boolean,
        peerId: DeviceId,
    ): Int {
        if (!peerWantsForeign) return 0
        var batches = 0
        val pending = mutableListOf<ByteString>()
        var size = 0

        suspend fun flush() {
            if (pending.isEmpty()) return
            send(ch, SyncMessage(batch = EntryBatch(entries = pending.toList())))
            batches++
            pending.clear()
            size = 0
        }

        // Everything we hold above the peer's contiguous vector. Relaying above our own contig is safe: the peer
        // accounts for exactly the seqs it receives, so a gap stays a gap until someone (or the author) fills it.
        for (author in (membership.active.keys + membership.departed.keys).sorted()) {
            if (author == peerId) continue
            var after = peerVector[author]
            while (true) {
                val page = store.entriesAfter(author, after, PAGE)
                if (page.isEmpty()) break
                for (e in page) {
                    after = e.seq
                    if (!membership.acceptsEntryFrom(e.author, e.seq)) continue
                    if (e.bytes.size > BATCH_BYTES) {
                        log("skipping oversized entry ${e.author.hex}#${e.seq}")
                        continue
                    }
                    if (size + e.bytes.size + ENTRY_OVERHEAD > BATCH_BYTES) flush()
                    pending += e.bytes.toByteString()
                    size += e.bytes.size + ENTRY_OVERHEAD
                }
            }
        }
        flush()
        return batches
    }

    /** Sends [entries] as MembershipOffer pages within the batch byte budget; always at least one page. */
    private suspend fun sendOffer(ch: SecureChannel, entries: List<DecodedEntry>) {
        val pages = mutableListOf<List<ByteString>>()
        var page = mutableListOf<ByteString>()
        var size = 0
        for (e in entries) {
            if (e.bytes.size > BATCH_BYTES) {
                log("skipping oversized membership entry ${e.author.hex}#${e.seq}")
                continue
            }
            if (size + e.bytes.size + ENTRY_OVERHEAD > BATCH_BYTES) {
                pages += page
                page = mutableListOf()
                size = 0
            }
            page += e.bytes.toByteString()
            size += e.bytes.size + ENTRY_OVERHEAD
        }
        pages += page
        pages.forEachIndexed { i, entriesOfPage ->
            send(ch, SyncMessage(membership = MembershipOffer(entries = entriesOfPage, more = i < pages.lastIndex)))
        }
    }

    private fun decodeOrNull(raw: ByteString): DecodedEntry? = try {
        DecodedEntry.decode(raw.toByteArray())
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_INVALID = 10
        private const val PAGE = 200
        private const val BATCH_BYTES = 60_000
        private const val ENTRY_OVERHEAD = 8
    }
}
