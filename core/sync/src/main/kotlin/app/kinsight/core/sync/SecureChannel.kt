package app.kinsight.core.sync

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.NoiseHandshake
import app.kinsight.core.crypto.NoiseTransport
import app.kinsight.core.model.GroupId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
         * even exchange Hello; the protocol version range travels inside the encrypted Hello instead, so a
         * version mismatch reaches Reject(PROTOCOL_VERSION) rather than failing the handshake.
         */
        const val PROTOCOL_FAMILY = 1

        fun prologue(groupId: GroupId): ByteArray =
            "kinsight".encodeToByteArray() + groupId.bytes() + ByteBuffer.allocate(4).putInt(PROTOCOL_FAMILY).array()

        val HANDSHAKE_TIMEOUT: Duration = 30.seconds

        /** Runs the initiator side; a peer that stalls longer than [handshakeTimeout] gives an IOException. */
        suspend fun initiate(
            stream: ByteStream,
            staticKey: DhKey,
            prologue: ByteArray,
            handshakeTimeout: Duration = HANDSHAKE_TIMEOUT,
        ): SecureChannel {
            val hs = NoiseHandshake.initiator(staticKey, prologue)
            handshake(stream, handshakeTimeout) {
                Frames.write(stream, hs.writeMessage())
                hs.readMessage(Frames.read(stream))
                Frames.write(stream, hs.writeMessage())
            }
            return SecureChannel(stream, hs.split(), hs.remoteStaticPublicKey!!)
        }

        /** Runs the responder side; a peer that stalls longer than [handshakeTimeout] gives an IOException. */
        suspend fun accept(
            stream: ByteStream,
            staticKey: DhKey,
            prologue: ByteArray,
            handshakeTimeout: Duration = HANDSHAKE_TIMEOUT,
        ): SecureChannel {
            val hs = NoiseHandshake.responder(staticKey, prologue)
            handshake(stream, handshakeTimeout) {
                hs.readMessage(Frames.read(stream))
                Frames.write(stream, hs.writeMessage())
                hs.readMessage(Frames.read(stream))
            }
            return SecureChannel(stream, hs.split(), hs.remoteStaticPublicKey!!)
        }

        private suspend fun handshake(stream: ByteStream, timeout: Duration, steps: suspend () -> Unit) {
            val finished = try {
                withTimeoutOrNull(timeout) { steps() } != null // null only when our own timeout fired
            } catch (e: Exception) {
                stream.close()
                throw e
            }
            if (!finished) {
                stream.close()
                throw IOException("handshake timed out after $timeout")
            }
        }
    }
}
