package app.kinsight.core.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Drops the link for both ends once [byteBudget] bytes have been written in total. */
class LinkFaults(byteBudget: Long = Long.MAX_VALUE) {
    private val remaining = AtomicLong(byteBudget)
    internal var onDrop: () -> Unit = {}

    internal fun consume(n: Int) {
        if (remaining.addAndGet(-n.toLong()) < 0) {
            onDrop()
            throw IOException("link dropped")
        }
    }
}

object MemoryPipe {
    /** [capacity] bounds the number of in-flight writes per direction (a full pipe suspends the writer). */
    fun pair(faults: LinkFaults = LinkFaults(), capacity: Int = Channel.UNLIMITED): Pair<ByteStream, ByteStream> {
        val ab = Channel<ByteArray>(capacity)
        val ba = Channel<ByteArray>(capacity)
        faults.onDrop = {
            ab.close(IOException("link dropped"))
            ba.close(IOException("link dropped"))
        }
        return MemoryEnd(incoming = ba, outgoing = ab, faults) to MemoryEnd(incoming = ab, outgoing = ba, faults)
    }
}

private class MemoryEnd(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
    private val faults: LinkFaults,
) : ByteStream {
    private var buf = ByteArray(0)
    private var pos = 0

    override suspend fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            if (pos == buf.size) {
                buf = incoming.receiveCatching().getOrNull() ?: throw EOFException("stream closed")
                pos = 0
            }
            val k = minOf(n - filled, buf.size - pos)
            buf.copyInto(out, filled, pos, pos + k)
            pos += k
            filled += k
        }
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        faults.consume(bytes.size)
        try {
            outgoing.send(bytes.copyOf())
        } catch (e: ClosedSendChannelException) {
            throw IOException("stream closed", e)
        }
    }

    override fun close() {
        outgoing.close()
        incoming.close(IOException("stream closed by peer"))
    }
}
