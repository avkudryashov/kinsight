package app.kinsight.core.designsystem.component

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.kinsight.core.designsystem.R

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

/** "5 minutes ago", "yesterday" in the system locale (DateUtils). */
@Composable
fun relativeTime(ms: Long, nowMs: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()
