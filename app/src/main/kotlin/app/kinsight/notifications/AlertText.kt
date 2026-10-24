package app.kinsight.notifications

import android.content.Context
import android.text.format.DateUtils
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.formatDuration
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.feature.devicedetail.R as DetailR

/** Notification texts for the planner's alerts; every kind in AlertPlanner.INTEGRITY_KINDS has its own (tested). */
object AlertText {
    data class Content(val channel: String, val title: String, val body: String)

    /**
     * [nowMs]: only for the NoContact length. The wall clock by default: the notifier posts right after the planner
     * decided with its own now, so both read the same hour.
     */
    fun of(context: Context, alert: Alert, nowMs: Long = System.currentTimeMillis()): Content {
        fun at(ms: Long) = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        val channel = AlertChannels.of(AlertPlanner.level(alert))
        return when (alert) {
            // «When: …», then one line of what to do (U4). An interval already over says its length instead of the
            // state, and a location problem that ended asks for no fix.
            is Alert.Integrity -> {
                val ended = closedTitleOf(alert.kind)?.let { res -> alert.durationMs?.let { res to it } }
                val title = ended?.let { (res, ms) -> context.getString(res, alert.device, formatDuration(context.resources, ms)) }
                    ?: title(context, alert)
                val advice = if (ended != null && alert.kind in AlertPlanner.SELF_CLOSING) null else context.getString(adviceOf(alert.kind))
                Content(channel, title, listOfNotNull(context.getString(R.string.alert_at, at(alert.atMs)), advice).joinToString("\n"))
            }
            is Alert.NoContact -> Content(
                channel,
                // Spec §10 «Нет связи N часов».
                context.getString(R.string.alert_no_contact, alert.device, formatDuration(context.resources, (nowMs - alert.sinceMs).coerceAtLeast(0))),
                context.getString(R.string.alert_no_contact_body, at(alert.sinceMs)),
            )
            is Alert.NewParent -> Content(channel, context.getString(R.string.alert_new_parent, alert.name), context.getString(R.string.alert_new_parent_body))
            is Alert.Resolved -> error("Resolved cancels a notification, it has no text")
            is Alert.Summary -> Content(
                channel,
                context.getString(if (alert.weekly) R.string.alert_summary_weekly else R.string.alert_summary_daily),
                alert.lines.joinToString("\n") { (name, ms) -> "$name: ${formatDuration(context.resources, ms)}" },
            )
            is Alert.Threshold -> Content(channel, threshold(context, alert), context.getString(R.string.alert_threshold_body))
        }
    }

    /** A zone event names its zone (spec §8.1), a PIN unblock its app, the rest the kind's own title. */
    private fun title(context: Context, a: Alert.Integrity): String = when (a.kind) {
        StateEvent.Kind.ZONE_EXIT -> a.zoneName?.let { context.getString(R.string.alert_zone_exit, a.device, it) }
            ?: context.getString(R.string.alert_zone_exit_unnamed, a.device)
        StateEvent.Kind.ZONE_ENTER -> a.zoneName?.let { context.getString(R.string.alert_zone_enter, a.device, it) }
            ?: context.getString(R.string.alert_zone_enter_unnamed, a.device)
        // App blocking spec §5.3: the package, as the event list names it.
        StateEvent.Kind.BLOCK_OVERRIDDEN -> context.getString(R.string.alert_block_overridden, a.device, a.packageName.orEmpty())
        else -> context.getString(titleOf(a.kind), a.device)
    }

    private fun titleOf(kind: StateEvent.Kind): Int = when (kind) {
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
        StateEvent.Kind.LOCATION_PERMISSION_OFF -> R.string.alert_location_off
        StateEvent.Kind.LOCATION_DISABLED -> R.string.alert_location_disabled
        StateEvent.Kind.BLOCKING_OFF -> R.string.alert_blocking_off
        else -> R.string.alert_event
    }

    /** The title of an interval kind that ended, with its length (Alert.Integrity.durationMs); null for other kinds. */
    private fun closedTitleOf(kind: StateEvent.Kind): Int? = when (kind) {
        StateEvent.Kind.BLUETOOTH_OFF -> R.string.alert_bluetooth_off_for
        StateEvent.Kind.LOCATION_PERMISSION_OFF -> R.string.alert_location_off_for
        StateEvent.Kind.LOCATION_DISABLED -> R.string.alert_location_disabled_for
        StateEvent.Kind.BLOCKING_OFF -> R.string.alert_blocking_off_for
        else -> null
    }

    /** One line of what to do; every kind in AlertPlanner.INTEGRITY_KINDS has its own (tested). */
    private fun adviceOf(kind: StateEvent.Kind): Int = when (kind) {
        StateEvent.Kind.SHARING_PAUSED -> R.string.alert_advice_paused
        StateEvent.Kind.USAGE_ACCESS_REVOKED -> R.string.alert_advice_access_revoked
        StateEvent.Kind.LEFT_GROUP -> R.string.alert_advice_left
        StateEvent.Kind.SYNC_STOPPED -> R.string.alert_advice_sync_stopped
        StateEvent.Kind.NOTIFICATION_HIDDEN -> R.string.alert_advice_notification_hidden
        StateEvent.Kind.NEARBY_PERMISSION_OFF -> R.string.alert_advice_nearby_off
        StateEvent.Kind.APP_FORCE_STOPPED -> R.string.alert_advice_force_stopped
        StateEvent.Kind.BLUETOOTH_OFF -> R.string.alert_advice_bluetooth_off
        StateEvent.Kind.DATA_GAP -> R.string.alert_advice_data_gap
        StateEvent.Kind.CLOCK_CHANGED -> R.string.alert_advice_clock_changed
        StateEvent.Kind.LOCATION_PERMISSION_OFF -> R.string.alert_advice_location_off
        StateEvent.Kind.LOCATION_DISABLED -> R.string.alert_advice_location_disabled
        StateEvent.Kind.ZONE_EXIT -> R.string.alert_advice_zone_exit
        StateEvent.Kind.ZONE_ENTER -> R.string.alert_advice_zone_enter
        StateEvent.Kind.BLOCKING_OFF -> R.string.alert_advice_blocking_off
        StateEvent.Kind.BLOCK_OVERRIDDEN -> R.string.alert_advice_block_overridden
        else -> R.string.alert_advice_event
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
