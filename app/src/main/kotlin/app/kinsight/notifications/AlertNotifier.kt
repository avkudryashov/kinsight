package app.kinsight.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kinsight.MainActivity
import app.kinsight.R
import app.kinsight.core.data.Alert
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

    /** False when nothing could be posted (see [canPost]): the caller then keeps the alert for a later run. */
    @SuppressLint("MissingPermission") // canPost checks it right above the notify call
    fun post(context: Context, alert: Alert): Boolean {
        if (!canPost(context)) return false
        NotificationManagerCompat.from(context).notify(idOf(alert), build(context, alert))
        return true
    }

    /**
     * Alerts once per id (a replacement is silent), and the lock screen shows only a generic title: the child's name
     * and the event stay private until the phone is unlocked.
     */
    internal fun build(context: Context, alert: Alert): Notification {
        val t = AlertText.of(context, alert)
        val publicTitle = context.getString(
            if (alert is Alert.Summary) R.string.alert_public_summary else R.string.alert_public_integrity,
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
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    /**
     * An integrity alert opens that child's detail screen; a child that left (no detail any more) and every other
     * alert open the dashboard. The request code is the notification id, so each child keeps its own target.
     */
    private fun contentIntent(context: Context, alert: Alert): PendingIntent {
        val device = (alert as? Alert.Integrity)?.takeIf { it.kind != StateEvent.Kind.LEFT_GROUP }?.id?.hex
        val intent = if (device != null) MainActivity.openDevice(context, device) else MainActivity.open(context)
        return PendingIntent.getActivity(context, idOf(alert), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** One notification per child and kind of problem (a newer one replaces it); summaries share one id. */
    private fun idOf(alert: Alert): Int = when (alert) {
        is Alert.Integrity -> ("i" + alert.device + alert.kind.name).hashCode()
        is Alert.NoContact -> ("c" + alert.device).hashCode()
        is Alert.NewParent -> ("p" + alert.name).hashCode()
        is Alert.Summary -> SUMMARY_ID
    }

    private const val SUMMARY_ID = 100
}
