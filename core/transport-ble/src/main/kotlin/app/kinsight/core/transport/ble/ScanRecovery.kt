package app.kinsight.core.transport.ble

/**
 * Backoff for a failing background scan. A PendingIntent scan can return 0 from startScan and fail later through
 * ScanReceiver (EXTRA_ERROR_CODE), so rc 0 proves nothing: only a batch delivered without an error resets the backoff
 * ([onDelivered]). Otherwise every failure, synchronous or not, takes the next [RadioRetrySchedule] step (5 s, 30 s,
 * 2 min, then 15 min), never a fixed 5 s loop. Not thread-safe (the service's serial dispatcher).
 */
internal class ScanRecovery(private val schedule: RadioRetrySchedule = RadioRetrySchedule()) {
    /** startScan returned [rc]: the retry delay when it failed, null when it was accepted (not yet proven). */
    fun onStarted(rc: Int): Long? = if (rc == 0) null else schedule.next()

    /** A scan error delivered asynchronously: the retry delay. */
    fun onError(): Long = schedule.next()

    /** A batch arrived without an error: the scan works. */
    fun onDelivered() = schedule.reset()

    /** The radio stopped: a new radio starts from the first step. */
    fun reset() = schedule.reset()
}
