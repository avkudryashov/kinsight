package app.kinsight.core.usage

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageWorkTest {
    private val zone = ZoneId.of("Europe/Madrid")

    @Test
    fun rolloverRunsAMinuteAfterLocalMidnight() {
        val s = Script(zone, LocalDate.of(2026, 10, 9))
        assertEquals(31 * MIN, UsageWork.delayToRollover(s.ms("23:30"), zone))
        assertEquals(DAY + MIN - 1_000, UsageWork.delayToRollover(s.ms("00:00:01"), zone))
    }

    @Test
    fun rolloverFollowsTheRealLengthOfADstDay() {
        // 2026-10-25 has 25 hours in Madrid: from 00:00 to the next midnight is 25 h.
        val s = Script(zone, LocalDate.of(2026, 10, 25))
        assertEquals(25 * HOUR_MS + MIN, UsageWork.delayToRollover(s.ms("00:00"), zone))
    }
}
