package app.kinsight.core.transport.ble

/**
 * Repeating log lines (a «skip <peer>: cooldown» per scan batch, ten a second in fast mode) at most once per key per
 * [intervalMs] of [clock] (elapsedRealtime). Thread-safe; keeps one timestamp per key (peers and reasons are few).
 */
class LogLimiter(private val clock: () -> Long, private val intervalMs: Long = INTERVAL_MS) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun log(key: String, write: () -> Unit) {
        val now = clock()
        val at = last[key]
        if (at != null && now - at in 0 until intervalMs) return
        last[key] = now
        write()
    }

    companion object {
        const val INTERVAL_MS = 60_000L
    }
}
