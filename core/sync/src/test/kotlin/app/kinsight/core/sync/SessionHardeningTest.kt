package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Done
import app.kinsight.core.model.proto.EntryBatch
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.MembershipOffer
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.UsageSummary
import app.kinsight.core.model.proto.VectorAnnounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionHardeningTest {
    /**
     * Frames b legitimately reads beyond what its inbox holds: hello, vector announce and the batch the main loop is
     * applying (3), plus the frame the reader holds while suspended on a full inbox (1), plus margin.
     */
    private val FRAMES_OUTSIDE_INBOX = 10

    @Test
    fun sendWindowFitsInsideTheInbox() {
        // Batches + acks in flight, plus control messages (hello, announce, done, offers, reject).
        val controlMargin = 8
        assertTrue(SyncSession.SEND_WINDOW * 2 + controlMargin < SyncSession.INBOX_CAPACITY)
    }

    /**
     * Plays [member] against [host]'s honest session with a hand-made Hello: a stale membership hash (so the host offers
     * its membership, as to a phone that missed an op) and [wantsForeign]. Returns the host's result and every message
     * the host sent after its Hello.
     */
    private suspend fun actAs(member: Node, host: Node, wantsForeign: Boolean): Pair<SessionResult, List<SyncMessage>> = coroutineScope {
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(host.store.groupId)
        val session = async { SyncSession(host.store, host.policy).run(SecureChannel.accept(sb, host.keys.dh, prologue)) }
        val ch = SecureChannel.initiate(sa, member.keys.dh, prologue)
        val hello = assertNotNull(ch.next().hello)
        ch.put(SyncMessage(hello = Hello(protocol_version = PROTOCOL_VERSION, group_id = hello.group_id, membership_hash = ByteString.EMPTY, wants_foreign_entries = wantsForeign)))
        val got = mutableListOf<SyncMessage>()
        while (true) {
            val m = ch.next()
            got += m
            if (assertNotNull(m.membership).more.not()) break
        }
        ch.put(SyncMessage(membership = MembershipOffer(entries = emptyList(), more = false)))
        val announce = ch.next()
        assertNotNull(announce.vector_announce)
        got += announce
        ch.put(SyncMessage(vector_announce = VectorAnnounce(vector = member.store.versionVector().toProto())))
        while (true) {
            val m = ch.next()
            got += m
            if (m.batch != null) ch.put(SyncMessage(ack = BatchAck(vector = member.store.versionVector().toProto())))
            if (m.done != null) break
        }
        ch.put(SyncMessage(done = Done()))
        ch.put(SyncMessage(done = Done())) // bye: the host's closing handshake ends at once
        session.await() to got
    }

    @Test
    fun aChildAskingForForeignEntriesGetsMembershipButNoBatch() = runBlocking<Unit> {
        val (parent, other, child) = buildGroup(3, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
        parent.publish(other.author.summary(TODAY.toString(), 1)) // another member's data the parent would relay
        parent.publish(parent.author.categoryOverride("com.example.game", 1))
        val late = DeviceKeys.generate()
        val add = parent.author.addMember(late.sign.publicKey, late.dh.publicKey, "late", DeviceRole.CHILD)
        parent.publish(add)
        val (result, got) = withTimeout(30.seconds) { actAs(child, parent, wantsForeign = true) }
        assertEquals(0, assertIs<SessionResult.Completed>(result).sentBatches)
        assertTrue(got.none { it.batch != null }, "a child got ${got.count { it.batch != null }} batches")
        val offered = got.mapNotNull { it.membership }.flatMap { it.entries }.map { DecodedEntry.decode(it.toByteArray()) }
        assertTrue(add in offered, "the child must still receive the membership op")
    }

    @Test
    fun aParentAskingForForeignEntriesStillGetsThem() = runBlocking<Unit> {
        val (parent, other, child) = buildGroup(3, role = { if (it == 2) DeviceRole.CHILD else DeviceRole.PARENT })
        parent.publish(child.author.summary(TODAY.toString(), 1))
        val (result, got) = withTimeout(30.seconds) { actAs(other, parent, wantsForeign = true) }
        assertTrue(assertIs<SessionResult.Completed>(result).sentBatches >= 1)
        assertTrue(got.any { it.batch != null })
    }

    @Test
    fun largeTwoWayExchangeDoesNotDeadlockWithSlowApply() = runBlocking {
        val (a, b) = buildGroup(2)
        a.policy = a.policy.copy(summaryRetentionDays = 10_000)
        b.policy = b.policy.copy(summaryRetentionDays = 10_000)
        // About 2.5 KB per entry: ~200 batches of 60 KB each way, well over twice the inbox capacity.
        suspend fun fat(n: Node, totalMs: Long) = n.store.applyBatch(
            (0 until 5_000).map { i ->
                n.author.create {
                    it.copy(
                        usage_summary = UsageSummary(
                            local_date = TODAY.minusDays(i.toLong()).toString(),
                            zone_id = "Europe/Madrid",
                            total_ms = totalMs,
                            apps = (0 until 100).map { j -> AppTotal(package_name = "com.example.app$j", foreground_ms = j.toLong()) },
                        ),
                    )
                }
            },
        )
        fat(a, 1)
        fat(b, 2)
        a.store = SlowApplyStore(a.store)
        b.store = SlowApplyStore(b.store)
        val (ra, rb) = withTimeout(60.seconds) { syncPair(a, b, capacity = 1) }
        assertTrue((assertIs<SessionResult.Completed>(ra)).sentBatches > 130)
        assertTrue((assertIs<SessionResult.Completed>(rb)).sentBatches > 130)
    }

    @Test
    fun floodedInboxIsBoundedWhileABatchIsApplied() = runBlocking {
        val (a, b) = buildGroup(2)
        val gate = CompletableDeferred<Unit>()
        b.store = GatedStore(b.store, gate)
        val (sa, sb) = MemoryPipe.pair()
        val counted = CountingStream(sb)
        val prologue = SecureChannel.prologue(a.store.groupId)
        coroutineScope {
            val session = async { SyncSession(b.store, b.policy).run(SecureChannel.accept(counted, b.keys.dh, prologue)) }
            val ch = SecureChannel.initiate(sa, a.keys.dh, prologue)
            val hello = assertNotNull(ch.next().hello)
            ch.put(SyncMessage(hello = Hello(protocol_version = PROTOCOL_VERSION, group_id = hello.group_id, membership_hash = hello.membership_hash, wants_foreign_entries = false)))
            assertNotNull(ch.next().vector_announce)
            ch.put(SyncMessage(vector_announce = VectorAnnounce(vector = a.store.versionVector().toProto())))
            ch.put(SyncMessage(batch = EntryBatch(entries = listOf(a.author.summary(TODAY.toString(), 1).bytes.toByteString()))))
            repeat(1_000) { ch.put(SyncMessage(ack = BatchAck())) } // the unbounded MemoryPipe never blocks us
            delay(500)
            val read = counted.frames
            assertTrue(
                read <= SyncSession.INBOX_CAPACITY + FRAMES_OUTSIDE_INBOX,
                "b read $read frames while its main loop was blocked",
            )
            gate.complete(Unit)
            session.cancel()
            session.join()
            assertTrue(session.isCancelled, "session did not tear down")
        }
    }

    @Test
    fun rejectReachesThePeerWhenOurSenderIsMidFrame() = runBlocking {
        val (a, b) = buildGroup(2)
        // Enough of b's own entries for several 60 KB batches: b's sender is busy writing when it decides to reject.
        b.store.applyBatch((0 until 2_000).map { b.author.summary(TODAY.minusDays(it.toLong()).toString(), 1) })
        val tampered = (0 until 20).map { flip(a.author.summary(TODAY.minusDays(it.toLong()).toString(), 1)) }
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(a.store.groupId)
        coroutineScope {
            val session = async { SyncSession(b.store, b.policy).run(SecureChannel.accept(SlowCutStream(sb), b.keys.dh, prologue)) }
            val ch = SecureChannel.initiate(sa, a.keys.dh, prologue)
            val hello = assertNotNull(ch.next().hello)
            ch.put(SyncMessage(hello = Hello(protocol_version = PROTOCOL_VERSION, group_id = hello.group_id, membership_hash = hello.membership_hash, wants_foreign_entries = true)))
            assertNotNull(ch.next().vector_announce)
            ch.put(SyncMessage(vector_announce = VectorAnnounce(vector = a.store.versionVector().toProto())))
            ch.put(SyncMessage(batch = EntryBatch(entries = tampered.map { it.bytes.toByteString() })))
            val seen = mutableListOf<SyncMessage>()
            try {
                while (true) seen += ch.next()
            } catch (e: IOException) {
                // b hung up after rejecting
            }
            assertEquals(SessionResult.TooManyInvalid(a.keys.deviceId), session.await())
            assertTrue(seen.any { it.reject?.reason == Reject.Reason.TOO_MANY_INVALID }, "no reject among ${seen.size} messages")
        }
    }

    @Test
    fun anAbsurdOwnSeqFromAPeerIsIgnored() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(b.store.groupId)
        coroutineScope {
            val session = async { SyncSession(b.store, b.policy).run(SecureChannel.accept(sb, b.keys.dh, prologue)) }
            val ch = SecureChannel.initiate(sa, a.keys.dh, prologue)
            val hello = assertNotNull(ch.next().hello)
            ch.put(SyncMessage(hello = Hello(protocol_version = PROTOCOL_VERSION, group_id = hello.group_id, membership_hash = hello.membership_hash, wants_foreign_entries = false)))
            assertNotNull(ch.next().vector_announce)
            val huge = app.kinsight.core.model.proto.VectorItem(author_id = b.keys.deviceId.bytes().toByteString(), seq = SyncSession.MAX_OWN_SEQ_JUMP + 1)
            ch.put(SyncMessage(vector_announce = VectorAnnounce(vector = listOf(huge))))
            ch.put(SyncMessage(done = Done()))
            while (ch.next().done == null) Unit
            ch.put(SyncMessage(done = Done()))
            assertIs<SessionResult.Completed>(session.await())
        }
        assertEquals(0L, b.store.versionVector()[b.keys.deviceId])
    }
}

private suspend fun SecureChannel.next(): SyncMessage = SyncMessage.ADAPTER.decode(receive())

private suspend fun SecureChannel.put(m: SyncMessage) = send(SyncMessage.ADAPTER.encode(m))

/** Flips the last byte (inside the signature): decodes fine, fails verification. */
private fun flip(e: DecodedEntry): DecodedEntry =
    DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })

/** applyBatch waits for [gate], so the session's main loop is stuck while its reader keeps going. */
private class GatedStore(private val inner: EntryStore, private val gate: CompletableDeferred<Unit>) : EntryStore by inner {
    override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>) {
        gate.await()
        inner.applyBatch(entries, seen)
    }
}

/** Counts frames read (each frame starts with a 4-byte length header). */
private class CountingStream(private val inner: ByteStream) : ByteStream by inner {
    @Volatile
    var frames = 0

    override suspend fun readFully(n: Int): ByteArray {
        val bytes = inner.readFully(n)
        if (n == 4) frames++
        return bytes
    }
}

/** Emulates a BLE stream: a big write takes [delayMs]; cancelling a write in progress closes the link (cut frame). */
private class SlowCutStream(
    private val inner: ByteStream,
    private val delayMs: Long = 300,
    private val bigBytes: Int = 10_000,
) : ByteStream by inner {
    override suspend fun write(bytes: ByteArray) {
        if (bytes.size >= bigBytes) {
            try {
                delay(delayMs)
            } catch (e: CancellationException) {
                inner.close()
                throw e
            }
        }
        inner.write(bytes)
    }
}

/** Applying a batch takes a moment, as with a real database: the main loop is the slow side. */
private class SlowApplyStore(private val inner: EntryStore) : EntryStore by inner {
    override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>) {
        delay(2)
        inner.applyBatch(entries, seen)
    }
}
