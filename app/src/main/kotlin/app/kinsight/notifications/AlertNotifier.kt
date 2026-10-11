package app.kinsight.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
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
    fun post(context: Context, alert: Alert): Boolean = postAll(context, listOf(alert))

    /**
     * Posts [alerts] in order, then one group summary per child that still has an alert from this pass (U26: one
     * summary update per group, not per alert, so the per-app rate limit goes to the alerts). Stops at the first alert
     * that cannot be posted ([canPost]) and returns false; the summaries of what was posted still go up. [quiet] go
     * first, silent: re-posts that must not sound again (the open problems moved off the 0.7.x channel); in the same
     * pass as [alerts], so a resolve among them sees them as this pass's posts.
     */
    @SuppressLint("MissingPermission") // canPost checks it right above the notify calls
    fun postAll(context: Context, alerts: List<Alert>, quiet: List<Alert> = emptyList()): Boolean {
        val silent = quiet.size
        val nm = NotificationManagerCompat.from(context)
        // Per group: the latest alert (the summary's tap target) and the ids this pass left in it. A cancel or a notify
        // reaches NotificationManagerService asynchronously, so this pass keeps its own account.
        val posted = LinkedHashMap<String, Pair<Alert, MutableSet<Int>>>()
        var ok = true
        for ((index, alert) in (quiet + alerts).withIndex()) {
            if (alert is Alert.Resolved) {
                val cancelled = cancelledIds(alert)
                cancelled.forEach(nm::cancel)
                for (group in groupsOf(alert)) {
                    val left = posted[group]?.second?.apply { removeAll(cancelled) }
                    if (mayDropSummary(left)) dropEmptySummary(context, group, cancelled)
                }
                continue
            }
            if (!canPost(context)) {
                ok = false
                break
            }
            // Builds before device-keyed ids keyed a pause or hidden notification by the name: replace that one too.
            if (alert is Alert.Integrity && alert.id != null && alert.kind in AlertPlanner.CLOSERS) {
                nm.cancel(integrityId(alert.device, alert.kind))
            }
            nm.notify(idOf(alert), build(context, alert, silent = index < silent))
            val group = groupOf(alert) ?: continue
            val ids = posted[group]?.second ?: mutableSetOf()
            ids += idOf(alert)
            posted[group] = alert to ids
        }
        // The summary itself never sounds (GROUP_ALERT_CHILDREN).
        for ((group, entry) in posted) {
            if (entry.second.isNotEmpty()) nm.notify(summaryId(group), summary(context, entry.first, group))
        }
        return ok
    }

    /**
     * At the start of a pass: a child's summary whose alerts are all gone (swiped, tapped, or cancelled while the
     * service had not yet applied it) goes too, so no empty «event on a child's phone» row stays in the shade.
     */
    fun sweepSummaries(context: Context) {
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        active.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
            .mapNotNull { it.notification.group?.takeIf { g -> g.startsWith(GROUP) } }
            .distinct()
            .filter { isEmptyGroup(active, it, emptySet()) }
            .forEach { NotificationManagerCompat.from(context).cancel(summaryId(it)) }
    }

    /**
     * Alerts once per id (a replacement is silent), except a threshold: the planner re-posts it only for a new marker (a
     * new day of the child, a changed limit), so it sounds even while yesterday's one of the same rule is still in the
     * shade. The lock screen shows only a generic title: the child's name
     * and the event stay private until the phone is unlocked.
     */
    internal fun build(context: Context, alert: Alert, silent: Boolean = false): Notification {
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
            .setSilent(silent)
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

    /** A resolve's groups: the device-keyed one and the name-keyed one of 0.7.x (cleared on the upgrade). */
    private fun groupsOf(alert: Alert.Resolved): List<String> = listOf(GROUP + alert.id.hex, GROUP + alert.device)

    /** What a resolve cancels: the device-keyed notification and the name-keyed one of an older build. */
    internal fun cancelledIds(alert: Alert.Resolved): Set<Int> = setOf(idOf(alert), integrityId(alert.device, alert.kind))

    /** On a fixed channel (a parent's «Важное»), whatever its alerts' channels: it is silent anyway. */
    private fun summary(context: Context, alert: Alert, group: String): Notification =
        NotificationCompat.Builder(context, AlertChannels.IMPORTANT)
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

    /**
     * A group's summary goes once none of its alerts is left. [cancelled]: ids just cancelled, which the service may
     * still list (it applies a cancel asynchronously).
     */
    private fun dropEmptySummary(context: Context, group: String, cancelled: Set<Int>) {
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        if (isEmptyGroup(active, group, cancelled)) NotificationManagerCompat.from(context).cancel(summaryId(group))
    }

    /**
     * A resolve may look at the shade only when this pass left no alert in the group ([postedLeft]): the service lists
     * a fresh post with a delay, and cancelling a summary also cancels its queued children. Otherwise the summary
     * stays (re-posted at the end of the pass); a childless one goes at the next pass's sweep.
     */
    internal fun mayDropSummary(postedLeft: Set<Int>?): Boolean = postedLeft.isNullOrEmpty()

    /** No alert of [group] in [active] apart from its summary and the [cancelled] ids. */
    internal fun isEmptyGroup(active: Array<StatusBarNotification>, group: String, cancelled: Set<Int>): Boolean =
        active.none { it.notification.group == group && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 && it.id !in cancelled }

    internal fun summaryId(group: String): Int = ("g$group").hashCode()

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
    internal fun idOf(alert: Alert): Int = when (alert) {
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
