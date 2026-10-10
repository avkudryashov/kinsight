package app.kinsight.core.transport.ble

import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SessionResult
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real blocking sockets (TCP on loopback) stand in for BluetoothSocket: same blocking, same close semantics. */
class BlockingByteStreamTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
    private val accepted = server.accept()
    private val a = client.stream()
    private val b = accepted.stream()

    private fun Socket.stream() = BlockingByteStream(getInputStream(), getOutputStream(), closer = ::close)

    @AfterTest
    fun cleanUp() {
        client.close()
        accepted.close()
        server.close()
    }

    @Test
    fun bytesArriveInOrderAcrossWrites() = runBlocking<Unit> {
        a.write(byteArrayOf(1, 2, 3))
        a.write(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), b.readFully(4))
        assertContentEquals(byteArrayOf(5), b.readFully(1))
    }

    @Test
    fun cancelledReadClosesTheSocketPromptly() = runBlocking<Unit> {
        val reader = launch(Dispatchers.Default) { a.readFully(4) } // the peer never sends
        delay(100)
        withTimeout(2.seconds) { reader.cancelAndJoin() }
        assertTrue(client.isClosed)
        assertFailsWith<IOException> { b.readFully(1) } // the peer sees the link go
        assertFailsWith<IOException> { a.readFully(1) } // and the stream stays dead
    }

    @Test
    fun cancelledWriteMidFrameKillsTheStream() = runBlocking<Unit> {
        // The peer never reads, so one big write blocks once the socket buffers are full: a frame cut in half.
        val writer = launch(Dispatchers.Default) { a.write(ByteArray(32 shl 20)) }
        delay(300)
        assertTrue(writer.isActive, "the write should still be blocked")
        withTimeout(2.seconds) { writer.cancelAndJoin() }
        assertTrue(client.isClosed)
        assertFailsWith<IOException> { a.write(byteArrayOf(1)) }
    }

    @Test
    fun peerCloseIsAnEofException() = runBlocking<Unit> {
        b.close()
        assertFailsWith<EOFException> { a.readFully(1) }
    }

    @Test
    fun syncSessionRunsOverTheSockets() = runBlocking<Unit> {
        val group = TestGroup.create(2)
        val (d0, d1) = group.devices
        d0.store.applyBatch((0 until 300).map { d0.author.summary(TODAY.minusDays(it.toLong()).toString(), it.toLong()) })
        val prologue = SecureChannel.prologue(group.groupId)
        val r1 = async(Dispatchers.Default) { SyncSession(d1.store, d1.policy()).run(SecureChannel.accept(b, d1.keys.dh, prologue)) }
        val r0 = SyncSession(d0.store, d0.policy()).run(SecureChannel.initiate(a, d0.keys.dh, prologue))
        assertIs<SessionResult.Completed>(r0)
        assertIs<SessionResult.Completed>(r1.await())
        assertEquals(d0.store.versionVector(), d1.store.versionVector())
    }
}
