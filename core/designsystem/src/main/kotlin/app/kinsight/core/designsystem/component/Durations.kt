package app.kinsight.core.designsystem.component

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.kinsight.core.designsystem.R
import java.time.Instant
import java.time.ZoneId

/** "<1 min", "45 min", "3 h", "2 h 5 min" from the locale's resources (spec §9.8). */
fun formatDuration(resources: Resources, ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> resources.getString(R.string.ds_duration_under_minute)
        minutes < 60 -> resources.getString(R.string.ds_duration_minutes, minutes)
        minutes % 60 == 0L -> resources.getString(R.string.ds_duration_hours, minutes / 60)
        else -> resources.getString(R.string.ds_duration_hours_minutes, minutes / 60, minutes % 60)
    }
}

@Composable
fun durationText(ms: Long): String = formatDuration(LocalResources.current, ms)

/**
 * A past moment under a minute old, or one slightly ahead of this clock (a peer's or the service's stamp): «just now»
 * rather than DateUtils' «In 0 minutes».
 */
fun isJustNow(ms: Long, nowMs: Long): Boolean = nowMs - ms < 60_000

/**
 * "5 minutes ago", "yesterday" in the system locale (DateUtils), always set inside a phrase («visto hace 2 minutos»),
 * so lowercased: Spanish DateUtils starts with a capital «Hace».
 */
@Composable
fun relativeTime(ms: Long, nowMs: Long): String =
    midPhrase(DateUtils.getRelativeTimeSpanString(ms, nowMs, DateUtils.MINUTE_IN_MILLIS).toString())

/** [text] with its first letter lowercased, for a place inside a phrase. */
fun midPhrase(text: String): String = text.replaceFirstChar { it.lowercaseChar() }

/**
 * An absolute moment on the clock of [zone] (the viewer's by default): «14:05» on [nowMs]'s day there, «10 Oct, 14:05»
 * on another day (U3; the widget's form since stage 5b). It stays true on a screen left open, unlike «5 minutes ago».
 * The 12/24 h system setting is honoured.
 */
fun absoluteMoment(context: Context, ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val sameDay = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val flags = if (sameDay) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME
    }
    return inZone(context, ms, flags, zone)
}

/**
 * The child's clock at [ms] («09:00») when its zone [childZoneId] is at another offset than [viewerZone] at that
 * instant; null when the offsets agree or the zone does not parse (untrusted peer).
 */
fun childClock(context: Context, ms: Long, childZoneId: String?, viewerZone: ZoneId = ZoneId.systemDefault()): String? {
    val child = childZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
    val at = Instant.ofEpochMilli(ms)
    if (child.rules.getOffset(at) == viewerZone.rules.getOffset(at)) return null
    return inZone(context, ms, DateUtils.FORMAT_SHOW_TIME, child)
}

/**
 * When data is from (U3): [absoluteMoment] on the viewer's clock, followed by the child's own clock when that reads
 * differently: «15:00 (у ребёнка 09:00)».
 */
fun dataMoment(context: Context, ms: Long, nowMs: Long, childZoneId: String?, viewerZone: ZoneId = ZoneId.systemDefault()): String {
    val viewer = absoluteMoment(context, ms, nowMs, viewerZone)
    val child = childClock(context, ms, childZoneId, viewerZone) ?: return viewer
    return context.getString(R.string.ds_moment_with_child, viewer, child)
}

private fun inZone(context: Context, ms: Long, flags: Int, zone: ZoneId): String =
    DateUtils.formatDateRange(context, java.util.Formatter(StringBuilder(), currentLocale(context)), ms, ms, flags, zone.id).toString()

private fun currentLocale(context: Context) = context.resources.configuration.locales[0]
