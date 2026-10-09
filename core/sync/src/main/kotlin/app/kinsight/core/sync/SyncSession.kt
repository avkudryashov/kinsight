package app.kinsight.core.sync

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.Done
import app.kinsight.core.model.proto.EntryBatch
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val ourHash = Membership.hash(store.groupId, ourMembership).toByteString()
        send(
            ch,
            SyncMessage(
                hello = Hello(
                    protocol_version = PROTOCOL_VERSION,
                    group_id = groupBytes,
                    membership_hash = ourHash,
                    vector = store.versionVector().toProto(),
                    wants_foreign_entries = policy.showOthers,
                ),
            ),
        )
        val first = receive(ch)
        first.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
        val hello = first.hello ?: throw ProtocolException("expected hello")
        if (hello.protocol_version != PROTOCOL_VERSION) {
            reject(ch, Reject.Reason.PROTOCOL_VERSION)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false)
        }
        if (hello.group_id != groupBytes) {
            reject(ch, Reject.Reason.WRONG_GROUP)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.WRONG_GROUP, byPeer = false)
        }

        var known = ourMembership
        if (hello.membership_hash != ourHash) {
            send(ch, SyncMessage(membership = MembershipOffer(entries = ourMembership.map { it.bytes.toByteString() })))
            val offerMsg = receive(ch)
            offerMsg.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
            val offer = offerMsg.membership ?: throw ProtocolException("expected membership offer")
            known = ourMembership + offer.entries.mapNotNull { decodeOrNull(it) }
        }
        val membership = Membership.compute(store.groupId, known) ?: throw ProtocolException("no valid genesis")
        store.storeMembership(membership.effectiveEntries.toList())

        val peer = membership.activeMemberByDhKey(ch.remoteStaticKey)
        if (peer == null || policy.self !in membership.active) {
            reject(ch, Reject.Reason.NOT_A_MEMBER)
            return@coroutineScope SessionResult.Rejected(Reject.Reason.NOT_A_MEMBER, byPeer = false)
        }

        val validator = EntryValidator(store.groupId, membership, policy)
        val peerVector = VersionVector.fromProto(hello.vector)
        val sentTotal = CompletableDeferred<Int>()
        val sender = launch {
            val n = sendData(ch, membership, peerVector, hello.wants_foreign_entries, peer.id)
            send(ch, SyncMessage(done = Done()))
            sentTotal.complete(n)
        }

        var invalid = 0
        var received = 0
        var acks = 0
        var peerDone = false
        while (true) {
            if (peerDone && acks >= sentTotal.await()) break
            val msg = receive(ch)
            when {
                msg.batch != null -> {
                    val running = store.versionVector().entries.toMutableMap()
                    val accepted = mutableListOf<DecodedEntry>()
                    val seen = mutableMapOf<DeviceId, Long>()
                    for (raw in msg.batch!!.entries) {
                        val e = decodeOrNull(raw)
                        val verdict = if (e == null) Verdict.Invalid("undecodable") else validator.check(e, running[e.author] ?: 0L)
                        when (verdict) {
                            Verdict.Accept -> {
                                accepted += e!!
                                running[e.author] = e.seq
                            }
                            Verdict.Expired -> {
                                seen.merge(e!!.author, e.seq, ::maxOf)
                                running[e.author] = e.seq
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
                msg.done != null -> peerDone = true
                msg.reject != null -> {
                    sender.cancel()
                    return@coroutineScope SessionResult.Rejected(msg.reject!!.reason, byPeer = true)
                }
                else -> throw ProtocolException("unexpected message")
            }
        }
        sender.join()
        SessionResult.Completed(peer.id, received, sentTotal.await())
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

        for (author in store.versionVector().entries.keys.sorted()) {
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
