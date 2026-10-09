package app.kinsight.core.sync

import app.kinsight.core.crypto.NoiseHandshake
import app.kinsight.core.crypto.NoiseTransport
import app.kinsight.core.crypto.X25519KeyPair
import app.kinsight.core.model.GroupId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

class SecureChannel private constructor(
    private val stream: ByteStream,
    private val transport: NoiseTransport,
    val remoteStaticKey: ByteArray,
) {
    private val writeLock = Mutex()

    /** Safe to call from several coroutines; encryption order equals frame order. */
    suspend fun send(plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT) { "message too large: ${plaintext.size}" }
        writeLock.withLock { Frames.write(stream, transport.encrypt(plaintext)) }
    }

    /** Single reader only. */
    suspend fun receive(): ByteArray = transport.decrypt(Frames.read(stream))

    fun close() = stream.close()

    companion object {
        const val MAX_PLAINTEXT = 65_000

        /**
         * Wire-format family bound into the handshake. It changes only on a break so deep that peers cannot
         * even exchange Hello; the exact [PROTOCOL_VERSION] travels inside the encrypted Hello instead, so a
         * version mismatch reaches Reject(PROTOCOL_VERSION) rather than failing the handshake.
         */
        const val PROTOCOL_FAMILY = 1

        fun prologue(groupId: GroupId): ByteArray =
            "kinsight".encodeToByteArray() + groupId.bytes() + ByteBuffer.allocate(4).putInt(PROTOCOL_FAMILY).array()

        suspend fun initiate(stream: ByteStream, staticKey: X25519KeyPair, prologue: ByteArray): SecureChannel {
            val hs = NoiseHandshake.initiator(staticKey, prologue)
            try {
                Frames.write(stream, hs.writeMessage())
                hs.readMessage(Frames.read(stream))
                Frames.write(stream, hs.writeMessage())
            } catch (e: Exception) {
                stream.close()
                throw e
            }
            return SecureChannel(stream, hs.split(), hs.remoteStaticPublicKey!!)
        }

        suspend fun accept(stream: ByteStream, staticKey: X25519KeyPair, prologue: ByteArray): SecureChannel {
            val hs = NoiseHandshake.responder(staticKey, prologue)
            try {
                hs.readMessage(Frames.read(stream))
                Frames.write(stream, hs.writeMessage())
                hs.readMessage(Frames.read(stream))
            } catch (e: Exception) {
                stream.close()
                throw e
            }
            return SecureChannel(stream, hs.split(), hs.remoteStaticPublicKey!!)
        }
    }
}
