package app.kinsight.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Spec §8.1 «Вне зон: служба FOREGROUND_SERVICE_LOCATION с уведомлением». It only holds the foreground state and the
 * notification; the points come from the tick (LocationTickReceiver), which goes on without it. If Android refuses the
 * location type here (spec §14, a start from the background on Android 14+), it stops at once and the tick carries on.
 *
 * Start and stop meet through [wanted] and [foreground]: a service started with startForegroundService must reach
 * startForeground before anything stops it, or Android crashes the app. So it always goes foreground first and then
 * stops itself when it is no longer wanted; [AndroidCadence] stops it from outside only once it is foreground.
 */
class LocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.loc_channel), NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "location service refused: ${e.javaClass.simpleName}")
            foreground = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Written before reading [wanted], the mirror of AndroidCadence.stopService: one of the two sides sees the other.
        foreground = true
        if (!wanted) {
            foreground = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kinsight_location)
            .setContentTitle(getString(R.string.loc_notification_title))
            .setContentText(getString(R.string.loc_notification_body))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "kinsight-location"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "KinsightLocation"

        /** The engine wants the service (outside every zone). */
        @Volatile
        internal var wanted = false

        /** The service has called startForeground and is not being stopped: safe to stop from outside. */
        @Volatile
        internal var foreground = false
    }
}
