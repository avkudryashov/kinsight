package app.kinsight.notifications

import android.content.Context
import android.text.format.DateUtils
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.designsystem.component.formatDuration
import app.kinsight.core.model.proto.StateEvent

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
        }
    }
}
