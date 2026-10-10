package app.kinsight.core.transport.ble

/**
 * Android silently drops the sixth scan start within 30 s (it logs "App is scanning too frequently" and delivers
 * nothing). Every startScan goes through this: [waitMs] says how long to wait before the next start may go out.
 * Monotonic clock only; not thread-safe (the service calls it from its serial dispatcher).
 */
class ScanThrottle(private val maxStarts: Int = MAX_STARTS, private val windowMs: Long = WINDOW_MS) {
    private val starts = ArrayDeque<Long>()

    fun waitMs(nowMono: Long): Long {
        while (starts.isNotEmpty() && nowMono - starts.first() >= windowMs) starts.removeFirst()
        if (starts.size < maxStarts) return 0L
        return (starts.first() + windowMs - nowMono).coerceIn(0L, windowMs)
    }

    fun record(nowMono: Long) {
        starts.addLast(nowMono)
        while (starts.size > maxStarts) starts.removeFirst()
    }

    companion object {
        const val MAX_STARTS = 5
        const val WINDOW_MS = 30_000L
    }
}
