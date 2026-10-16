package app.kinsight.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Spec §8.1 «Вне зон: служба FOREGROUND_SERVICE_LOCATION с уведомлением». It only holds the foreground state and the
 * notification; the points come from the tick (LocationTickReceiver), which goes on without it. If Android refuses the
 * location type here (spec §14, a start from the background on Android 14+), it stops at once and the tick carries on.
 *
 * Start and stop meet in [ServiceGate]: a service started with startForegroundService must reach startForeground
 * before anything stops it, or Android crashes the app. So every start command goes foreground first and then stops
 * the service itself (by its own start id) when it is no longer wanted; [AndroidCadence] stops it from outside only
 * when it is foreground with no start pending.
 */
class LocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.loc_channel), NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) {
            Log.w(TAG, "location service refused: ${e.javaClass.simpleName}")
            false
        }
        // By start id: a newer start already sent keeps the service and gets its own onStartCommand (and check).
        if (ServiceGate.started(ok)) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceGate.destroyed()
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
    }
}

/**
 * The one place start and stop of [LocationService] are decided, under one monitor (never held across a call into
 * Android): whether the engine wants it, whether it is in the foreground, and how many startForegroundService calls
 * have not reached onStartCommand yet. While a start is pending nothing stops the service from outside (its own
 * onStartCommand checks [wanted] after going foreground) and no second start is sent. A pending start older than
 * [PENDING_EXPIRY_MS] is forgotten: Android gives a started service about 10 s to call startForeground, so by then it
 * either ran or never will (a start dropped without an error must not block the service for good).
 */
internal object ServiceGate {
    const val PENDING_EXPIRY_MS = 15_000L

    /** Elapsed realtime; replaced in tests. */
    @Volatile
    var clock: () -> Long = SystemClock::elapsedRealtime

    private var wanted = false
    private var foreground = false
    private var pending = 0
    private var pendingSinceMs = 0L

    private fun expire() {
        if (pending > 0 && clock() - pendingSinceMs >= PENDING_EXPIRY_MS) pending = 0
    }

    /** The engine wants the service; true when the caller must send startForegroundService (counted as pending). */
    @Synchronized
    fun wantStart(): Boolean {
        wanted = true
        expire()
        if (foreground || pending > 0) return false
        pending++
        pendingSinceMs = clock()
        return true
    }

    /** startForegroundService threw: that start never comes. */
    @Synchronized
    fun startFailed() {
        if (pending > 0) pending--
    }

    /** The engine no longer wants it; true when the caller must stop it now (foreground, no start pending). */
    @Synchronized
    fun wantStop(): Boolean {
        wanted = false
        expire()
        if (!foreground || pending > 0) return false
        foreground = false
        return true
    }

    /** A start command reached the service ([ok]: it is foreground); true when it must stop itself. */
    @Synchronized
    fun started(ok: Boolean): Boolean {
        if (pending > 0) pending--
        foreground = ok && wanted
        return !foreground
    }

    @Synchronized
    fun destroyed() {
        foreground = false
    }

    @get:Synchronized
    val isForeground: Boolean get() = foreground

    @get:Synchronized
    val pendingStarts: Int get() {
        expire()
        return pending
    }

    @Synchronized
    fun reset() {
        wanted = false
        foreground = false
        pending = 0
        pendingSinceMs = 0L
        clock = SystemClock::elapsedRealtime
    }
}
