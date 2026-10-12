package app.kinsight.core.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailRangeTest {
    private val monFirst = WeekFields.of(DayOfWeek.MONDAY, 4)
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)
    private val saturday = LocalDate.of(2026, 10, 10)

    @Test
    fun dayIsTheAnchorItself() {
        val r = DetailRange.of(Period.DAY, saturday, monFirst)
        assertEquals(DetailRange(Period.DAY, saturday, saturday), r)
        assertEquals(listOf(saturday), r.dates())
    }

    @Test
    fun weekFollowsTheLocalesFirstDay() {
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)), DetailRange.of(Period.WEEK, saturday, monFirst))
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10)), DetailRange.of(Period.WEEK, saturday, sunFirst))
        // A Sunday is the last day of a Monday-first week and the first of a Sunday-first one.
        val sunday = LocalDate.of(2026, 10, 11)
        assertEquals(LocalDate.of(2026, 10, 5), DetailRange.of(Period.WEEK, sunday, monFirst).start)
        assertEquals(sunday, DetailRange.of(Period.WEEK, sunday, sunFirst).start)
        assertEquals(7, DetailRange.of(Period.WEEK, sunday, sunFirst).dates().size)
    }

    @Test
    fun weekAcrossTheYearBoundary() {
        val newYear = LocalDate.of(2027, 1, 1) // a Friday
        val r = DetailRange.of(Period.WEEK, newYear, monFirst)
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), r)
        assertEquals((28..31).map { LocalDate.of(2026, 12, it) } + (1..3).map { LocalDate.of(2027, 1, it) }, r.dates())
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)), r.next())
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 27)), r.previous())
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 2)), DetailRange.of(Period.WEEK, newYear, sunFirst))
    }

    @Test
    fun monthIsTheCalendarMonthWithLeapFebruary() {
        val feb = DetailRange.of(Period.MONTH, LocalDate.of(2028, 2, 14), monFirst)
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)), feb)
        assertEquals(29, feb.dates().size)
        assertEquals(28, DetailRange.of(Period.MONTH, LocalDate.of(2027, 2, 14), monFirst).dates().size)
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 31)), feb.previous())
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 31)), feb.next())
        val oct = DetailRange.of(Period.MONTH, saturday, sunFirst)
        assertEquals(31, oct.dates().size)
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), oct.previous())
    }

    @Test
    fun previousAndNextStepOnePeriod() {
        val day = DetailRange.of(Period.DAY, LocalDate.of(2026, 3, 1), monFirst)
        assertEquals(DetailRange(Period.DAY, LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 28)), day.previous())
        assertEquals(day, day.previous().next())
        val week = DetailRange.of(Period.WEEK, saturday, monFirst)
        assertEquals(DetailRange(Period.WEEK, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4)), week.previous())
        assertEquals(week, week.next().previous())
    }
}
