package app.kinsight.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kinsight.MainActivity
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.model.proto.StateEvent

object AlertNotifier {
    /**
     * The app may post at all: notifications on and POST_NOTIFICATIONS granted (minSdk 31; the permission exists from
     * 33). A single blocked channel still counts as allowed: the user chose to silence that kind.
     */
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /**
     * False when nothing could be posted (see [canPost]): the caller then keeps the alert for a later run. A
     * [Alert.Resolved] posts nothing: it cancels that child's open notification of its kind (no permission needed).
     */
    @SuppressLint("MissingPermission") // canPost checks it right above the notify calls
    fun post(context: Context, alert: Alert): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (alert is Alert.Resolved) {
            nm.cancel(idOf(alert))
            // An older build keyed the open notification by the child's name: that one goes too.
            nm.cancel(integrityId(alert.device, alert.kind))
            dropEmptySummary(context, groupOf(alert))
            return true
        }
        if (!canPost(context)) return false
        // Builds before device-keyed ids keyed a pause or hidden notification by the name: replace that one too.
        if (alert is Alert.Integrity && alert.id != null && alert.kind in AlertPlanner.CLOSERS) {
            nm.cancel(integrityId(alert.device, alert.kind))
        }
        nm.notify(idOf(alert), build(context, alert))
        // U26: a child's alerts are bundled; the summary itself never sounds (GROUP_ALERT_CHILDREN).
        groupOf(alert)?.let { nm.notify(summaryId(it), summary(context, alert, it)) }
        return true
    }

    /**
     * Alerts once per id (a replacement is silent), except a threshold: the planner re-posts it only for a new marker (a
     * new day of the child, a changed limit), so it sounds even while yesterday's one of the same rule is still in the
     * shade. The lock screen shows only a generic title: the child's name
     * and the event stay private until the phone is unlocked.
     */
    internal fun build(context: Context, alert: Alert): Notification {
        val t = AlertText.of(context, alert)
        val publicTitle = context.getString(
            when (alert) {
                is Alert.Summary -> R.string.alert_public_summary
                is Alert.Threshold -> R.string.alert_public_threshold
                is Alert.NewParent -> R.string.alert_public_transparency
                else -> R.string.alert_public_integrity
            },
        )
        val public = NotificationCompat.Builder(context, t.channel)
            .setSmallIcon(R.drawable.ic_stat_kinsight)
            .setContentTitle(publicTitle)
            .build()
        return NotificationCompat.Builder(context, t.channel)
            .setSmallIcon(R.drawable.ic_stat_kinsight)
            .setContentTitle(t.title)
            .setContentText(t.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.body))
            .setContentIntent(contentIntent(context, alert))
            .setAutoCancel(true)
            .setOnlyAlertOnce(alert !is Alert.Threshold)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply { groupOf(alert)?.let(::setGroup) }
            .build()
    }

    /** One group per child (U26); summaries and the child's own «new parent» are not grouped. */
    internal fun groupOf(alert: Alert): String? = when (alert) {
        is Alert.Integrity -> GROUP + (alert.id?.hex ?: alert.device)
        is Alert.Resolved -> GROUP + alert.id.hex
        is Alert.NoContact -> GROUP + (alert.id?.hex ?: alert.device)
        is Alert.Threshold -> GROUP + alert.child.hex
        is Alert.NewParent, is Alert.Summary -> null
    }

    private fun summary(context: Context, alert: Alert, group: String): Notification =
        NotificationCompat.Builder(context, AlertText.of(context, alert).channel)
            .setSmallIcon(R.drawable.ic_stat_kinsight)
            .setContentTitle(context.getString(R.string.alert_public_integrity))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent(context, alert))
            .build()

    /** A group's summary goes once none of its alerts is left in the shade. */
    private fun dropEmptySummary(context: Context, group: String?) {
        group ?: return
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        val left = active.count { it.notification.group == group && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        if (left == 0) NotificationManagerCompat.from(context).cancel(summaryId(group))
    }

    private fun summaryId(group: String): Int = ("g$group").hashCode()

    /**
     * An integrity or threshold alert opens that child's detail screen; a child that left (no detail any more) and
     * every other alert open the dashboard. The request code is the notification id, so each child keeps its own target.
     */
    private fun contentIntent(context: Context, alert: Alert): PendingIntent {
        val device = when (alert) {
            is Alert.Integrity -> alert.takeIf { it.kind != StateEvent.Kind.LEFT_GROUP }?.id?.hex
            is Alert.Threshold -> alert.child.hex
            else -> null
        }
        val intent = if (device != null) MainActivity.openDevice(context, device) else MainActivity.open(context)
        return PendingIntent.getActivity(context, idOf(alert), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * One notification per child and kind of problem (a newer one replaces it); summaries share one id. An integrity
     * id is keyed by the child's device (its name only when unknown), so a [Alert.Resolved] finds it after a rename.
     * A threshold is keyed by its rule (child and target): a re-post the next day or after a limit change replaces it.
     */
    private fun idOf(alert: Alert): Int = when (alert) {
        is Alert.Integrity -> integrityId(alert.id?.hex ?: alert.device, alert.kind)
        is Alert.Resolved -> integrityId(alert.id.hex, alert.kind)
        is Alert.NoContact -> ("c" + (alert.id?.hex ?: alert.device)).hashCode()
        is Alert.NewParent -> ("p" + (alert.id?.hex ?: alert.name)).hashCode()
        is Alert.Summary -> SUMMARY_ID
        is Alert.Threshold -> ("t" + ThresholdRule.idOf(alert.child, alert.target)).hashCode()
    }

    private fun integrityId(child: String, kind: StateEvent.Kind): Int = ("i" + child + kind.name).hashCode()

    private const val SUMMARY_ID = 100

    private const val GROUP = "kinsight-child-"
}
