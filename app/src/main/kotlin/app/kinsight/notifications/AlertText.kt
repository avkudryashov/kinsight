package app.kinsight.notifications

import android.content.Context
import android.text.format.DateUtils
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.formatDuration
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.feature.devicedetail.R as DetailR

/** Notification texts for the planner's alerts; every kind in AlertPlanner.INTEGRITY_KINDS has its own (tested). */
object AlertText {
    data class Content(val channel: String, val title: String, val body: String)

    fun of(context: Context, alert: Alert): Content {
        fun at(ms: Long) = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        return when (alert) {
            is Alert.Integrity -> Content(
                AlertChannels.INTEGRITY,
                context.getString(
                    when (alert.kind) {
                        StateEvent.Kind.SHARING_PAUSED -> R.string.alert_paused
                        StateEvent.Kind.USAGE_ACCESS_REVOKED -> R.string.alert_access_revoked
                        StateEvent.Kind.LEFT_GROUP -> R.string.alert_left
                        StateEvent.Kind.SYNC_STOPPED -> R.string.alert_sync_stopped
                        StateEvent.Kind.NOTIFICATION_HIDDEN -> R.string.alert_notification_hidden
                        StateEvent.Kind.NEARBY_PERMISSION_OFF -> R.string.alert_nearby_off
                        StateEvent.Kind.APP_FORCE_STOPPED -> R.string.alert_force_stopped
                        StateEvent.Kind.BLUETOOTH_OFF -> R.string.alert_bluetooth_off
                        StateEvent.Kind.DATA_GAP -> R.string.alert_data_gap
                        StateEvent.Kind.CLOCK_CHANGED -> R.string.alert_clock_changed
                        else -> R.string.alert_event
                    },
                    alert.device,
                ),
                context.getString(R.string.alert_at, at(alert.atMs)),
            )
            is Alert.NoContact -> Content(
                AlertChannels.INTEGRITY,
                context.getString(R.string.alert_no_contact, alert.device),
                context.getString(R.string.alert_no_contact_body, at(alert.sinceMs)),
            )
            is Alert.NewParent -> Content(
                AlertChannels.INTEGRITY,
                context.getString(R.string.alert_new_parent, alert.name),
                context.getString(R.string.alert_new_parent_body),
            )
            is Alert.Resolved -> error("Resolved cancels a notification, it has no text")
            is Alert.Summary -> Content(
                AlertChannels.SUMMARIES,
                context.getString(if (alert.weekly) R.string.alert_summary_weekly else R.string.alert_summary_daily),
                alert.lines.joinToString("\n") { (name, ms) -> "$name: ${formatDuration(context.resources, ms)}" },
            )
            is Alert.Threshold -> Content(AlertChannels.THRESHOLDS, threshold(context, alert), context.getString(R.string.alert_threshold_body))
        }
    }

    /** «Kid: 3 h 10 min today (limit 3 h)»; a category or app is named («Kid: Games 1 h 5 min today (limit 1 h)»). */
    private fun threshold(context: Context, alert: Alert.Threshold): String {
        val used = formatDuration(context.resources, alert.usedMs)
        val limit = formatDuration(context.resources, alert.limitMs)
        val target = when (val t = alert.target) {
            ThresholdTarget.Total -> return context.getString(R.string.alert_threshold_total, alert.device, used, limit)
            is ThresholdTarget.Category -> context.getString(categoryName(t.category))
            is ThresholdTarget.App -> alert.targetLabel ?: t.packageName
        }
        return context.getString(R.string.alert_threshold_target, alert.device, target, used, limit)
    }

    /** The category names of the detail screen (feature/device-detail), so both read the same. */
    private fun categoryName(c: AppCategory): Int = when (c) {
        AppCategory.GAMES -> DetailR.string.det_cat_games
        AppCategory.SOCIAL -> DetailR.string.det_cat_social
        AppCategory.VIDEO -> DetailR.string.det_cat_video
        AppCategory.AUDIO -> DetailR.string.det_cat_audio
        AppCategory.PHOTOS -> DetailR.string.det_cat_photos
        AppCategory.NEWS -> DetailR.string.det_cat_news
        AppCategory.MAPS -> DetailR.string.det_cat_maps
        AppCategory.PRODUCTIVITY -> DetailR.string.det_cat_productivity
        AppCategory.OTHER -> DetailR.string.det_cat_other
    }
}
