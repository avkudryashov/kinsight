package app.kinsight.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.kinsight.R
import app.kinsight.core.data.Alert

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
        val t = AlertText.of(context, alert)
        // The launcher entry: the main activity once the app has one, the diagnostics screen until then.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(context, t.channel)
            .setSmallIcon(R.drawable.ic_stat_kinsight)
            .setContentTitle(t.title)
            .setContentText(t.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(idOf(alert), n)
        return true
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
