package app.kinsight.core.usage

import kotlin.math.abs

/** Wall clock, elapsed-since-boot clock and boot count, sampled together. */
data class ClockSample(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

/** Detects a wall-clock change (spec §7, ACTION_TIME_CHANGED with its shift) from two samples. */
object ClockWatch {
    const val THRESHOLD_MS = 60_000L

    /** The jump of the wall clock beyond what elapsed time explains, or null within [THRESHOLD_MS]. */
    fun shift(prev: ClockSample, now: ClockSample): Long? {
        val shift = if (prev.bootCount == now.bootCount && now.elapsedMs >= prev.elapsedMs) {
            now.wallMs - (prev.wallMs + (now.elapsedMs - prev.elapsedMs))
        } else {
            // Across a reboot the elapsed clock restarts: only a wall clock behind the previous sample is visible.
            minOf(0L, now.wallMs - prev.wallMs)
        }
        return shift.takeIf { abs(it) >= THRESHOLD_MS }
    }
}
