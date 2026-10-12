package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun syncAndPairingSharingOneBudgetNeverExceedFivePerWindow() {
        val process = ScanThrottle()
        val pairing = process.share(maxStarts = 2)
        val starts = mutableListOf<Long>()
        val pairingStarts = mutableListOf<Long>()
        // Both users try to start every 500 ms for two minutes, the pairing dial offset by 250 ms.
        var t = 0L
        while (t < 120_000L) {
            if (process.tryStart(t) == 0L) starts += t
            if (pairing.tryStart(t + 250) == 0L) {
                starts += t + 250
                pairingStarts += t + 250
            }
            t += 500
        }
        for (s in starts) assertTrue(starts.count { it in s until s + ScanThrottle.WINDOW_MS } <= 5, "window at $s")
        for (s in pairingStarts) assertTrue(pairingStarts.count { it in s until s + ScanThrottle.WINDOW_MS } <= 2, "pairing window at $s")
        assertTrue(pairingStarts.isNotEmpty())
    }

    @Test
    fun pairingFullOwnCapWaitsEvenWhenTheProcessHasRoom() {
        val process = ScanThrottle()
        val pairing = process.share(maxStarts = 2)
        assertEquals(0L, pairing.tryStart(0L))
        assertEquals(0L, pairing.tryStart(1L))
        assertEquals(ScanThrottle.WINDOW_MS, pairing.tryStart(1_000L) + 1_000L)
        assertEquals(0L, process.tryStart(1_000L)) // the process still has 3 slots
    }

    @Test
    fun aDeferredStartRecordsNothing() {
        val process = ScanThrottle()
        val pairing = process.share(maxStarts = 2)
        repeat(5) { assertEquals(0L, process.tryStart(it.toLong())) }
        assertTrue(pairing.tryStart(10L) > 0)
        // The refused pairing start took no slot: once the window passes all five slots are free again.
        repeat(5) { assertEquals(0L, process.tryStart(ScanThrottle.WINDOW_MS + 10 + it)) }
    }
}
