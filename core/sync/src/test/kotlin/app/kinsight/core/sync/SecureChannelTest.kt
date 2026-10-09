package app.kinsight.core.sync

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecureChannelTest {
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "g").groupId
    private val prologue = SecureChannel.prologue(groupId, 1)

    @Test
    fun handshakeAndExchange() = runBlocking {
        val a = X25519.generate()
        val b = X25519.generate()
        val (sa, sb) = MemoryPipe.pair()
        coroutineScope {
            val ca = async { SecureChannel.initiate(sa, a, prologue) }
            val cb = async { SecureChannel.accept(sb, b, prologue) }
            val chA = ca.await()
            val chB = cb.await()
            assertContentEquals(b.publicKey, chA.remoteStaticKey)
            assertContentEquals(a.publicKey, chB.remoteStaticKey)
            chA.send("hi".encodeToByteArray())
            chB.send(ByteArray(SecureChannel.MAX_PLAINTEXT) { 7 })
            assertEquals("hi", chB.receive().decodeToString())
            assertEquals(SecureChannel.MAX_PLAINTEXT, chA.receive().size)
        }
    }

    @Test
    fun oversizedPlaintextRejected(): Unit = runBlocking {
        val (sa, sb) = MemoryPipe.pair()
        coroutineScope {
            val ca = async { SecureChannel.initiate(sa, X25519.generate(), prologue) }
            async { SecureChannel.accept(sb, X25519.generate(), prologue) }.await()
            assertFailsWith<IllegalArgumentException> { ca.await().send(ByteArray(SecureChannel.MAX_PLAINTEXT + 1)) }
        }
    }

    @Test
    fun differentGroupsFailHandshake() = runBlocking {
        val other = SecureChannel.prologue(GroupGenesis.create(DeviceKeys.generate(), "x").groupId, 1)
        val (sa, sb) = MemoryPipe.pair()
        coroutineScope {
            val ca = async { runCatching { SecureChannel.initiate(sa, X25519.generate(), prologue) } }
            val cb = async { runCatching { SecureChannel.accept(sb, X25519.generate(), other) } }
            val ra = ca.await()
            val rb = cb.await()
            val failure = ra.exceptionOrNull() ?: rb.exceptionOrNull()
            assertEquals(true, failure is CryptoException || failure is IOException, "got $failure")
        }
    }

    @Test
    fun droppedLinkGivesEofToBothSides() = runBlocking {
        val (sa, sb) = MemoryPipe.pair(LinkFaults(byteBudget = 10))
        coroutineScope {
            val ca = async { runCatching { SecureChannel.initiate(sa, X25519.generate(), prologue) } }
            val cb = async { runCatching { SecureChannel.accept(sb, X25519.generate(), prologue) } }
            assertEquals(true, ca.await().exceptionOrNull() is IOException)
            assertEquals(true, cb.await().exceptionOrNull() is IOException)
        }
    }

    @Test
    fun oversizedFrameHeaderRejected(): Unit = runBlocking {
        val (sa, sb) = MemoryPipe.pair()
        sa.write(byteArrayOf(0x7f, 0, 0, 0))
        assertFailsWith<IOException> { Frames.read(sb) }
    }
}
