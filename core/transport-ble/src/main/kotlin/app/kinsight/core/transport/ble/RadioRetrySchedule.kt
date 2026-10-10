package app.kinsight.core.transport.ble

/**
 * When to retry a radio that would not start although the adapter is on (RADIO_ERROR): 5 s, 30 s, 2 min, then every
 * 15 min for as long as it keeps failing. [reset] after a successful start or when Bluetooth goes off. Not thread-safe
 * (the service calls it from its serial dispatcher).
 */
class RadioRetrySchedule(private val delaysMs: List<Long> = DELAYS_MS, private val steadyMs: Long = STEADY_MS) {
    private var attempt = 0

    fun next(): Long = (delaysMs.getOrNull(attempt) ?: steadyMs).also { attempt++ }

    fun reset() {
        attempt = 0
    }

    companion object {
        val DELAYS_MS = listOf(5_000L, 30_000L, 120_000L)
        const val STEADY_MS = 15 * 60_000L
    }
}
