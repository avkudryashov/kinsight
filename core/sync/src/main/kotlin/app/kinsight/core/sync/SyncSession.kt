package app.kinsight.core.sync

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.CloudHandoff
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Done
import app.kinsight.core.model.proto.EntryBatch
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.VectorAnnounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.ProtocolException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 2: CategoryOverride entries (stage 5c).
 * 3: cloud storage, location and safe zones; one CloudHandoff each way after the vectors.
 * The highest version this build speaks.
 */
const val PROTOCOL_VERSION = 3

/**
 * The lowest version this build speaks (A-1). Raise it only when the oldest phones in the field no longer matter: a
 * peer whose range ends below it gets PROTOCOL_VERSION in Hello, before any entry.
 */
const val MIN_PROTOCOL_VERSION = 2

/**
 * The first version whose builds carry the cloud (spec §6, §7). Only a joiner that speaks it is handed the refresh token
 * (§7.3); a member on an older build never holds a token, so a token revoke does not wait for its Heartbeat.
 */
const val CLOUD_PROTOCOL_VERSION = 3

sealed interface SessionResult {
    /** [protocolVersion]: the version both sides agreed on in Hello. */
    data class Completed(val peer: DeviceId, val received: Int, val sentBatches: Int, val protocolVersion: Int) : SessionResult {
        /**
         * Both vectors (canonical bytes) as exchanged at the start of the session plus the authors' completions the
         * session made (ours of the peer's log, the peer's of ours), so they are what the next session starts from when
         * nothing else moved; the transport's «nothing new» rule keys on them. Outside equals on purpose (arrays);
         * empty when not recorded.
         */
        var ourVector: ByteArray = ByteArray(0)
            internal set
        var peerVector: ByteArray = ByteArray(0)
            internal set

        /**
         * Nothing we sent moved the peer's vector: no batch, or its final ack equals the vector it announced. Entries it
         * stored above a gap do not move it either; they are delivered all the same. With [received] zero the session
         * counts as empty for the transport's «nothing new» rule: running it again would change nothing.
         */
        var peerAckUnchanged: Boolean = sentBatches == 0
            internal set
    }
    /** [peerNewer] (PROTOCOL_VERSION only): true when the peer speaks only newer versions (update this phone), false when only older ones. */
    data class Rejected(val reason: Reject.Reason, val byPeer: Boolean, val peerNewer: Boolean? = null) : SessionResult
    data class TooManyInvalid(val peer: DeviceId) : SessionResult
    data class Failed(val cause: Throwable) : SessionResult
}

/**
 * One symmetric sync session over an established [SecureChannel]. Both sides run the same code.
 * [versions]: the protocol range we offer. [legacyHello]: for mixed-version tests in this module only, speak like
 * 0.7.2 (no range fields in Hello, exact match on protocol_version = versions.first). The primary constructor is
 * internal so no other module can ship a client that drops the range; it has no default for [legacyHello], which
 * keeps calls without it on the public constructor.
 */
class SyncSession internal constructor(
    private val store: EntryStore,
    private val policy: SyncPolicy,
    private val versions: IntRange = ProtocolVersions.SUPPORTED,
    private val idleTimeout: Duration = 30.seconds,
    private val sessionTimeout: Duration = 5.minutes,
    private val lingerTimeout: Duration = LINGER,
    private val log: (String) -> Unit = {},
    private val legacyHello: Boolean,
    private val handoff: SessionHandoff? = null,
) {
    constructor(
        store: EntryStore,
        policy: SyncPolicy,
        versions: IntRange = ProtocolVersions.SUPPORTED,
        idleTimeout: Duration = 30.seconds,
        sessionTimeout: Duration = 5.minutes,
        lingerTimeout: Duration = LINGER,
        log: (String) -> Unit = {},
        handoff: SessionHandoff? = null,
    ) : this(store, policy, versions, idleTimeout, sessionTimeout, lingerTimeout, log, legacyHello = false, handoff = handoff)

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

    /**
     * Sends [reason] before stopping our sender. Cancelling a sender in the middle of a frame closes a BLE stream (the
     * cut frame would corrupt it), and a notice sent after that is lost. The write lock is fair, so the notice goes out
     * right after the frame in flight; only then is the sender cancelled. A link that does not move within
     * [REJECT_GRACE] gets no notice.
     */
    private suspend fun rejectThenStop(ch: SecureChannel, sender: Job, reason: Reject.Reason) {
        withTimeoutOrNull(REJECT_GRACE) { reject(ch, reason) }
        sender.cancel()
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
                    protocol_version = versions.first,
                    group_id = groupBytes,
                    membership_hash = ourHash,
                    wants_foreign_entries = policy.showOthers,
                    min_protocol_version = if (legacyHello) 0 else versions.first,
                    max_protocol_version = if (legacyHello) 0 else versions.last,
                ),
            ),
        )
        val first = receive(ch)
        first.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
        val hello = first.hello ?: throw ProtocolException("expected hello")
        val peerVersions = ProtocolVersions.of(hello)
        val agreed = when {
            legacyHello -> hello.protocol_version.takeIf { it == versions.first }
            peerVersions == null -> null
            else -> ProtocolVersions.negotiate(versions, peerVersions)
        }
        if (agreed == null) {
            log("version reject: ours $versions, peer ${peerVersions ?: "malformed (protocol_version ${hello.protocol_version}, range ${hello.min_protocol_version}..${hello.max_protocol_version})"}")
            reject(ch, Reject.Reason.PROTOCOL_VERSION)
            val peerNewer = peerVersions?.let { it.first > versions.last }
            return@coroutineScope SessionResult.Rejected(Reject.Reason.PROTOCOL_VERSION, byPeer = false, peerNewer = peerNewer)
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
        val ourVector = store.versionVector()
        send(ch, SyncMessage(vector_announce = VectorAnnounce(vector = ourVector.toProto())))
        val announceMsg = receive(ch)
        announceMsg.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
        val announce = announceMsg.vector_announce ?: throw ProtocolException("expected vector announce")
        val peerVector = VersionVector.fromProto(announce.vector)
        // A-4: an authenticated member holding our own log above what we hold means our database lost entries while our
        // keys survived. Account those seqs, which also lifts our author state past them (RoomEntryStore.authorState),
        // so we never sign a seq the family already holds. Bounded: a bogus peer cannot fling our counter to the end.
        val ownSeen = peerVector[policy.self]
        val ownHeld = ourVector[policy.self]
        if (ownSeen > ownHeld) {
            if (ownSeen - ownHeld <= MAX_OWN_SEQ_JUMP) {
                store.completeAuthor(policy.self, ownSeen)
                log("own log seen at $ownSeen by ${peer.id.hex}, held $ownHeld: those seqs are not reused")
            } else {
                log("ignored own seq $ownSeen from ${peer.id.hex} (held $ownHeld)")
            }
        }

        // Spec §6 «Раздача» between paired devices (protocol 3): one CloudHandoff each way, empty when there is nothing.
        // A protocol 2 peer never sees it. Who may hand what (only the storage owner) is the handoff's decision. A local
        // fault in the handoff is contained (logged without the token); a peer breaking the order still fails the session.
        if (agreed >= CLOUD_PROTOCOL_VERSION) {
            val offer = handoff?.let { h -> contained("offer to ${peer.id.hex}") { h.offer(peer, membership) } }
            send(
                ch,
                SyncMessage(
                    cloud_handoff = CloudHandoff(
                        storage_generation = offer?.storageGeneration ?: 0,
                        storage_id = offer?.storageId?.toByteString() ?: ByteString.EMPTY,
                        access_token = offer?.accessToken.orEmpty(),
                        expires_at_ms = offer?.expiresAtMs ?: 0,
                    ),
                ),
            )
            val m = receive(ch)
            m.reject?.let { return@coroutineScope SessionResult.Rejected(it.reason, byPeer = true) }
            val got = m.cloud_handoff ?: throw ProtocolException("expected cloud handoff")
            HandedAccess.of(got.storage_generation, got.storage_id.toByteArray(), got.access_token, got.expires_at_ms)
                ?.let { access -> handoff?.let { h -> contained("receive from ${peer.id.hex}") { h.receive(peer, membership, access) } } }
        }

        val validator = EntryValidator(store.groupId, membership, policy)
        val sentTotal = CompletableDeferred<Int>()
        // One permit per batch in flight: taken before a batch is sent, returned when the peer acks it.
        val window = Semaphore(SEND_WINDOW)
        val sender = launch {
            try {
                // S-H1: the role decides, not the peer's flag. A CHILD takes membership (spec §4.8), which already went
                // both ways in the offer exchange above, and, from protocol 3, the parents' FROM_PARENTS_TO_ALL entries
                // (its PIN, safe zones and storage access, spec §10, §8.1), whatever its Hello says. A protocol 2 child
                // gets no batch at all, as before.
                val peerTakesForeign = hello.wants_foreign_entries && !peer.isChild
                val scope = when {
                    peerTakesForeign -> SendScope.ALL
                    agreed >= CLOUD_PROTOCOL_VERSION -> SendScope.FROM_PARENTS
                    else -> SendScope.NONE
                }
                val n = sendData(ch, membership, peerVector, scope, peer.id, window)
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
        // Bounded: a peer that floods us suspends our reader (and, through the link, itself) instead of filling memory.
        // Invariant: an honest peer has at most SEND_WINDOW batches unacked, so at most SEND_WINDOW batches (from it) and
        // SEND_WINDOW acks (for ours) plus a few control messages are ever unread here; that is below INBOX_CAPACITY
        // (checked in SessionHardeningTest), so our reader always keeps draining the link and our sends cannot stall
        // behind a peer whose reader is blocked on a full inbox.
        val inbox = Channel<SyncMessage>(INBOX_CAPACITY)
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
            // The lowest seq of the peer's own log that we (showOthers off) dropped this session (Skipped or Unwanted): a
            // share entry we cannot take yet ([holdsBack]). Completion stops below it.
            var heldBack = Long.MAX_VALUE
            var completedTo = 0L
            var lastAck: VersionVector? = null // the peer's vector in its last ack of our batches
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
                        // A-2: the newest live set per author in this batch, applied after the batch itself.
                        val liveSets = HashMap<DeviceId, LiveSet>()
                        fun noteLiveSet(e: DecodedEntry) {
                            val set = LiveSet.of(e) ?: return
                            val known = liveSets[e.author]
                            if (known == null || set.upTo > known.upTo) liveSets[e.author] = set
                        }
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
                                    noteLiveSet(e)
                                }
                                Verdict.Expired -> {
                                    seen.getOrPut(e!!.author) { mutableSetOf() } += e.seq
                                    inBatch += e.author to e.seq
                                }
                                Verdict.Duplicate -> noteLiveSet(e!!) // still useful while gaps remain below it
                                Verdict.Unwanted -> if (holdsBack(e!!, peer.id)) heldBack = minOf(heldBack, e.seq)
                                is Verdict.Invalid -> {
                                    invalid++
                                    log("invalid entry from ${peer.id.hex}: ${verdict.reason}")
                                }
                                is Verdict.Skipped -> {
                                    log("skipped ${e!!.author.hex.take(8)}#${e.seq} from ${peer.id.hex}: ${verdict.reason}")
                                    if (holdsBack(e, peer.id)) heldBack = minOf(heldBack, e.seq)
                                }
                            }
                        }
                        if (invalid > MAX_INVALID) {
                            rejectThenStop(ch, sender, Reject.Reason.TOO_MANY_INVALID)
                            return@coroutineScope SessionResult.TooManyInvalid(peer.id)
                        }
                        store.applyBatch(accepted, seen)
                        for ((author, set) in liveSets) store.accountLiveSet(author, set)
                        received += accepted.size
                        send(ch, SyncMessage(ack = BatchAck(vector = store.versionVector().toProto())))
                    }
                    msg.ack != null -> {
                        acks++
                        lastAck = VersionVector.fromProto(msg.ack!!.vector)
                        // A stray extra ack from a misbehaving peer must not break the window.
                        try {
                            window.release()
                        } catch (e: IllegalStateException) {
                            log("surplus ack from ${peer.id.hex}")
                        }
                    }
                    msg.done != null -> {
                        peerDone = true
                        // Author completion: the peer has now sent every entry of its own log above our announced
                        // contig that we take (all its batches precede Done), so its own announced seq closes any gap
                        // we have. With showOthers on, that is its whole log. With it off (a child), from protocol 3 it
                        // is every FROM_PARENTS_TO_ALL entry of a parent (SendScope.FROM_PARENTS), the only kinds we
                        // keep of it, so the rest of its log is accounted for and our position follows its own (no
                        // resend in the next session). A protocol 2 peer sends a child nothing: no completion then.
                        // Only the author completes its own log: a relay never makes us skip a seq of another author.
                        // A child never completes over a share entry it dropped in this session ([holdsBack]): a later
                        // build that takes it (a wider FROM_PARENTS_TO_ALL, a relaxed rule) still receives it then.
                        if (policy.showOthers || agreed >= CLOUD_PROTOCOL_VERSION) {
                            completedTo = minOf(peerVector[peer.id], heldBack - 1)
                            store.completeAuthor(peer.id, completedTo)
                        }
                    }
                    msg.reject != null -> {
                        sender.cancel()
                        return@coroutineScope SessionResult.Rejected(msg.reject!!.reason, byPeer = true)
                    }
                    else -> throw ProtocolException("unexpected message")
                }
            }
            // The vectors as the session left them, but only by the authors' completions: ours of the peer's log, and the
            // peer's of ours (it completes ours whenever it asked for foreign entries or the session ran on protocol 3+).
            // A session that only completed a log is then «nothing new» at once, while an entry written meanwhile still
            // counts as new. Not the full end vectors: those would hide a publish made during the session.
            val ourEnd = ourVector.with(peer.id, maxOf(ourVector[peer.id], completedTo))
            val peerCompletes = agreed >= CLOUD_PROTOCOL_VERSION || hello.wants_foreign_entries
            val peerEnd = if (peerCompletes) peerVector.with(policy.self, maxOf(peerVector[policy.self], ourVector[policy.self])) else peerVector
            val completed = SessionResult.Completed(peer.id, received, sentBatches, agreed).also {
                it.ourVector = ourEnd.canonicalBytes()
                it.peerVector = peerEnd.canonicalBytes()
                // Our batches moved nothing on the peer when its last ack still shows the vector it announced: e.g. an
                // older child holding back a share entry it cannot take yet. Entries it stored above a gap leave its
                // vector unchanged too; they are delivered, and sending them again would only repeat duplicates.
                it.peerAckUnchanged = sentBatches == 0 || lastAck == peerVector
            }
            closingHandshake(ch, inbox)
            completed
        } finally {
            reader.cancel()
            sender.cancel()
        }
    }

    /**
     * Keeps the link open until the peer has finished too. A transport may drop frames still in flight when a socket is
     * closed (BLE L2CAP does), so the side that completes first must not hang up on the peer's last needed message (our
     * Done or an ack). Each side, once complete, sends a second Done ("bye") and waits, bounded by [lingerTimeout], for
     * the peer's bye or for the peer to hang up. A bye is only sent after completion, so whoever receives it knows the
     * peer needs nothing more and may close. Symmetric: no roles, and two complete sides release each other within one
     * round trip. A peer that predates the bye just closes, which ends our wait as well.
     */
    private suspend fun closingHandshake(ch: SecureChannel, inbox: Channel<SyncMessage>) {
        try {
            send(ch, SyncMessage(done = Done()))
        } catch (e: IOException) {
            return // the peer already hung up: it had everything it needed
        } catch (e: CryptoException) {
            return
        }
        withTimeoutOrNull(lingerTimeout) {
            while (true) {
                val m = inbox.receiveCatching().getOrNull() ?: break // hung up or the link failed
                if (m.done != null) break
            }
        }
    }

    /** Runs a [SessionHandoff] call; a local exception is logged (type only, never the message: it might quote a token). */
    private suspend fun <T> contained(what: String, call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("cloud handoff $what failed: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun sendData(
        ch: SecureChannel,
        membership: MembershipState,
        peerVector: VersionVector,
        scope: SendScope,
        peerId: DeviceId,
        window: Semaphore,
    ): Int {
        if (scope == SendScope.NONE) return 0
        var batches = 0
        val pending = mutableListOf<ByteString>()
        var size = 0

        suspend fun flush() {
            if (pending.isEmpty()) return
            window.acquire()
            send(ch, SyncMessage(batch = EntryBatch(entries = pending.toList())))
            batches++
            pending.clear()
            size = 0
        }

        // Everything we hold above the peer's contiguous vector. Relaying above our own contig is safe: the peer
        // accounts for exactly the seqs it receives, so a gap stays a gap until someone (or the author) fills it.
        // For a child (SendScope.FROM_PARENTS): only parents' entries of the share kinds (SyncPolicy.shareKinds), the
        // ones its EntryValidator takes (the admin check is the reader's, as on every phone). Child authors are skipped
        // before any read on purpose, so siblings' logs cost nothing; a parent's log is read through the kind index
        // (EntryStore.entriesAfter with kinds), not page by page. Selected per kind, so the child accounts those seqs
        // only; the parent's own completion (at its Done) then lifts the child's position past the rest of its log.
        for (author in (membership.active.keys + membership.departed.keys).sorted()) {
            if (author == peerId) continue
            if (scope == SendScope.FROM_PARENTS && membership.roleOf(author) == DeviceRole.CHILD) continue
            var after = peerVector[author]
            while (true) {
                val page = if (scope == SendScope.FROM_PARENTS) {
                    store.entriesAfter(author, after, policy.shareKinds, PAGE)
                } else {
                    store.entriesAfter(author, after, PAGE)
                }
                if (page.isEmpty()) break
                for (e in page) {
                    after = e.seq
                    if (!membership.acceptsEntryFrom(e.author, e.seq)) continue
                    if (scope == SendScope.FROM_PARENTS && e.kind !in policy.shareKinds) continue
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

    /**
     * An entry of [peerId]'s own log that we (showOthers off) did not take, whatever its kind. A peer sends a child only
     * its own share set (SendScope.FROM_PARENTS), so such an entry is by construction a share entry this build cannot
     * take yet: a kind it does not know (UNKNOWN), a known kind outside its [SyncPolicy.shareKinds], or one its rules
     * refuse. Completion must not account it.
     */
    private fun holdsBack(e: DecodedEntry, peerId: DeviceId): Boolean = !policy.showOthers && e.author == peerId

    /** What [sendData] sends: everything, a child's share (parents' FROM_PARENTS_TO_ALL entries), or nothing. */
    private enum class SendScope { ALL, FROM_PARENTS, NONE }

    private fun decodeOrNull(raw: ByteString): DecodedEntry? = try {
        DecodedEntry.decode(raw.toByteArray())
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_INVALID = 10

        /** Largest own-seq lift one session accepts from a peer's vector (A-4); about 90 years of honest writes. */
        const val MAX_OWN_SEQ_JUMP = 10_000_000L

        /** Messages buffered between the dedicated reader and the main loop. */
        const val INBOX_CAPACITY = 64

        /** Maximum batches sent but not yet acked by the peer. */
        const val SEND_WINDOW = 16
        private val REJECT_GRACE = 5.seconds
        /** Upper bound of the closing handshake when the peer neither says bye nor hangs up. */
        val LINGER = 5.seconds
        private const val PAGE = 200
        /** Byte budget of one EntryBatch; an entry above it is never sent (core/usage caps UsageDetail by it). */
        const val BATCH_BYTES = 60_000
        private const val ENTRY_OVERHEAD = 8
    }
}
