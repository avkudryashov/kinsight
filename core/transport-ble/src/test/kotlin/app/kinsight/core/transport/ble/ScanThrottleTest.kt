package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanThrottleTest {
    @Test
    fun fiveStartsPassThenTheSixthWaitsForTheOldestToAge() {
        val t = ScanThrottle()
        for (i in 0 until 5) {
            assertEquals(0L, t.waitMs(1_000L + i))
            t.record(1_000L + i)
        }
        assertEquals(ScanThrottle.WINDOW_MS, t.waitMs(1_004L) + 4)
        assertEquals(0L, t.waitMs(1_000L + ScanThrottle.WINDOW_MS))
    }

    @Test
    fun startsOutsideTheWindowAreForgotten() {
        val t = ScanThrottle()
        repeat(5) { t.record(0L) }
        assertEquals(0L, t.waitMs(ScanThrottle.WINDOW_MS))
        t.record(ScanThrottle.WINDOW_MS)
        assertEquals(0L, t.waitMs(ScanThrottle.WINDOW_MS + 1))
    }

    @Test
    fun aClockGoingBackDoesNotBlockForever() {
        val t = ScanThrottle()
        repeat(5) { t.record(100_000L) }
        // elapsedRealtime never goes back, but a bogus value must still give a bounded wait.
        assertEquals(ScanThrottle.WINDOW_MS, t.waitMs(100_000L))
        assertEquals(true, t.waitMs(0L) <= ScanThrottle.WINDOW_MS)
    }
}
