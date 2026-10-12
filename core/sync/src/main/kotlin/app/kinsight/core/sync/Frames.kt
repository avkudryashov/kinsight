package app.kinsight.core.sync

import java.net.ProtocolException
import java.nio.ByteBuffer

object Frames {
    const val MAX_FRAME = 70_000

    suspend fun write(stream: ByteStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME) { "frame too large: ${payload.size}" }
        stream.write(ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array())
    }

    suspend fun read(stream: ByteStream): ByteArray {
        val n = ByteBuffer.wrap(stream.readFully(4)).int
        if (n < 0 || n > MAX_FRAME) throw ProtocolException("bad frame length $n")
        return stream.readFully(n)
    }
}
