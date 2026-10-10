package app.kinsight.core.transport.ble

import app.kinsight.core.sync.ByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the blocking [block] on [executor]. If the caller is cancelled, [onCancel] runs (it closes the socket), which
 * makes the blocked call fail and frees the thread; the caller resumes at once with CancellationException and the
 * late result of [block] is dropped. Blocking BluetoothSocket calls ignore thread interrupts, so this is the only way
 * to cancel them.
 */
internal suspend fun <T> runClosingOnCancel(executor: Executor, onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { onCancel() }
        executor.execute {
            val result = runCatching(block)
            if (cont.isActive) cont.resumeWith(result)
        }
    }

/**
 * A [ByteStream] over a blocking socket (BLE L2CAP CoC). Cancelling a read or a write closes the socket: a frame cut
 * in half would corrupt the encrypted stream anyway, and a broken link is just an unfinished session (spec §10).
 * Safe for one reader and one writer at a time, which is how SecureChannel uses it.
 */
class BlockingByteStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit,
    private val executor: Executor = Dispatchers.IO.asExecutor(),
) : ByteStream {
    private val closed = AtomicBoolean(false)

    override suspend fun readFully(n: Int): ByteArray {
        ensureOpen()
        return runClosingOnCancel(executor, ::close) {
            val out = ByteArray(n)
            var filled = 0
            while (filled < n) {
                val k = input.read(out, filled, n - filled)
                if (k < 0) throw EOFException("stream closed")
                filled += k
            }
            out
        }
    }

    override suspend fun write(bytes: ByteArray) {
        ensureOpen()
        runClosingOnCancel(executor, ::close) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closer()
        } catch (_: IOException) {
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IOException("stream closed")
    }
}
