package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioRetryScheduleTest {
    @Test
    fun backsOffThenSettlesOnAQuarterHour() {
        val s = RadioRetrySchedule()
        assertEquals(listOf(5_000L, 30_000L, 120_000L, 900_000L, 900_000L, 900_000L), List(6) { s.next() })
    }

    @Test
    fun resetStartsOver() {
        val s = RadioRetrySchedule()
        repeat(4) { s.next() }
        s.reset()
        assertEquals(5_000L, s.next())
        assertEquals(30_000L, s.next())
    }
}
