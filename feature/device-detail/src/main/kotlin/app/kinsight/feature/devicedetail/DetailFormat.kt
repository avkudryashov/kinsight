package app.kinsight.feature.devicedetail

import android.icu.text.DateFormat
import android.icu.text.DateIntervalFormat
import android.icu.text.NumberFormat
import android.icu.util.DateInterval
import android.icu.util.TimeZone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.designsystem.component.formatDuration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Midnight UTC of [date]: ICU formats it in UTC, so the label is the date itself whatever the phone's zone. */
private fun utcMs(date: LocalDate): Long = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun skeleton(skeleton: String, locale: Locale, ms: Long): String =
    DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }.format(ms)

private fun String.capitalized(locale: Locale) = replaceFirstChar { it.titlecase(locale) }

/** A day by name: «Today», «Yesterday», else the weekday and date in the locale's order ("Tue, Oct 6", «Вт, 6 окт.»). */
@Composable
internal fun dayTitle(date: LocalDate, today: LocalDate): String =
    dayTitle(date, today, currentLocale(), stringResource(R.string.det_today), stringResource(R.string.det_yesterday))

/** [dayTitle] outside composition, with the «Today» and «Yesterday» texts given. */
internal fun dayTitle(date: LocalDate, today: LocalDate, locale: Locale, todayText: String, yesterdayText: String): String = when (date) {
    today -> todayText
    today.minusDays(1) -> yesterdayText
    else -> skeleton(if (date.year == today.year) "MMMEd" else "yMMMEd", locale, utcMs(date)).capitalized(locale)
}

/** Each day bar's TalkBack text (U10): «Tue, Oct 6: 2 h 10 min», or the day and «no data»; a tap's action is its label. */
@Composable
internal fun rememberBarTexts(days: List<DayTotal>, today: LocalDate, locale: Locale): List<String> {
    val todayText = stringResource(R.string.det_today)
    val yesterdayText = stringResource(R.string.det_yesterday)
    val describe = stringResource(R.string.det_bar_description)
    val noData = stringResource(R.string.det_bar_no_data)
    val resources = LocalResources.current
    return remember(days, today, locale, todayText, yesterdayText, describe, noData, resources) {
        days.map { d ->
            val value = if (d.known) formatDuration(resources, d.totalMs) else noData
            String.format(locale, describe, dayTitle(d.date, today, locale, todayText, yesterdayText), value)
        }
    }
}

/** Day and month in the locale's order and case ("October 6", «6 октября»). */
internal fun dayMonth(date: LocalDate, locale: Locale): String = skeleton("MMMMd", locale, utcMs(date))

/** The period header's title: a day by [dayTitle], a week as a date interval ("Oct 4 – 10"), a month with its year. */
@Composable
internal fun rangeTitle(range: DetailRange, today: LocalDate): String {
    val locale = currentLocale()
    return when (range.period) {
        Period.DAY -> dayTitle(range.start, today)
        Period.WEEK -> {
            val sameYear = range.start.year == today.year && range.end.year == today.year
            DateIntervalFormat.getInstance(if (sameYear) "MMMd" else "yMMMd", locale)
                .apply { timeZone = TimeZone.GMT_ZONE }
                .format(DateInterval(utcMs(range.start), utcMs(range.end)))
        }
        Period.MONTH -> skeleton("yMMMM", locale, utcMs(range.start)).capitalized(locale)
    }
}

/**
 * Clock hour [hour] (0..23) for chart axes: "6 PM" in the locale's 12-hour format, else the bare hour in the locale's
 * digits ("18"; ICU's 24-hour pattern pads it to "06" in some locales, too wide for an axis tick).
 */
internal fun hourLabel(hour: Int, locale: Locale, is24Hour: Boolean): String =
    if (is24Hour) NumberFormat.getIntegerInstance(locale).format(hour) else skeleton("ha", locale, hour * 3_600_000L)

/** Clock hour [hour] as a time for sentences (chart summaries): "8 PM" or "20:00" by the locale's clock. */
internal fun hourTime(hour: Int, locale: Locale, is24Hour: Boolean): String =
    skeleton(if (is24Hour) "Hm" else "ha", locale, hour * 3_600_000L)

@Composable
private fun currentLocale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
