package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.BatchAck
import app.kinsight.core.model.proto.EntryBatch
import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.Reject
import app.kinsight.core.model.proto.SyncMessage
import app.kinsight.core.model.proto.VectorAnnounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionHardeningTest {
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
            assertTrue(read <= SyncSession.INBOX_CAPACITY + 10, "b read $read frames while its main loop was blocked")
            gate.complete(Unit)
            session.cancel()
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
