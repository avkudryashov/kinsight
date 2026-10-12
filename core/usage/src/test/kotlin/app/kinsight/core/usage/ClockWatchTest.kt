package app.kinsight.core.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockWatchTest {
    private val prev = ClockSample(wallMs = 10 * HOUR_MS, elapsedMs = 5 * HOUR_MS, bootCount = 4)

    @Test
    fun steadyClockAndSmallDriftAreNotAChange() {
        assertNull(ClockWatch.shift(prev, ClockSample(11 * HOUR_MS, 6 * HOUR_MS, 4)))
        assertNull(ClockWatch.shift(prev, ClockSample(11 * HOUR_MS + 30_000, 6 * HOUR_MS, 4)))
    }

    @Test
    fun jumpsBeyondTheThresholdAreReportedWithTheirSign() {
        assertEquals(HOUR_MS, ClockWatch.shift(prev, ClockSample(12 * HOUR_MS, 6 * HOUR_MS, 4)))
        assertEquals(-HOUR_MS, ClockWatch.shift(prev, ClockSample(10 * HOUR_MS, 6 * HOUR_MS, 4)))
    }

    @Test
    fun acrossARebootOnlyAClockBehindThePreviousSampleShows() {
        assertNull(ClockWatch.shift(prev, ClockSample(30 * HOUR_MS, 1_000, 5)))
        assertEquals(-HOUR_MS, ClockWatch.shift(prev, ClockSample(9 * HOUR_MS, 1_000, 5)))
    }
}
