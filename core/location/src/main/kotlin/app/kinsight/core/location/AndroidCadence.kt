package app.kinsight.core.location

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Spec §8.1 cadence on Android: the location service, the 5-minute tick (`setAndAllowWhileIdle`: no exact-alarm
 * permission, Doze may stretch it; spec §14 measures it), the in-zone check (WorkManager).
 */
class AndroidCadence internal constructor(context: Context, start: ((Intent) -> ComponentName?)?) : Cadence {
    constructor(context: Context) : this(context, null)

    private val app = context.applicationContext

    /** startForegroundService (a seam for tests): null when the system dropped the start without saying why. */
    private val start: (Intent) -> ComponentName? = start ?: app::startForegroundService
    private val alarms = app.getSystemService(AlarmManager::class.java)

    /**
     * Sends no second start while one is pending or the service is foreground (the tick follows every 5 minutes). A
     * start that throws or that the system drops (null) is uncounted at once, so the next tick tries again.
     */
    override fun startService(): Boolean {
        if (!ServiceGate.wantStart()) return true
        return try {
            if (start(Intent(app, LocationService::class.java)) != null) {
                true
            } else {
                ServiceGate.startFailed()
                Log.w(TAG, "location service start dropped")
                false
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            ServiceGate.startFailed()
            Log.w(TAG, "location service not allowed from the background") // spec §14, Android 12+ / 14+
            false
        } catch (e: Exception) {
            ServiceGate.startFailed()
            Log.w(TAG, "location service refused: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Only a foreground service with no start pending is stopped from here; one still starting goes foreground and then
     * stops itself (LocationService), never «startForegroundService() did not then call startForeground()».
     */
    override fun stopService() {
        if (ServiceGate.wantStop()) app.stopService(Intent(app, LocationService::class.java))
    }

    override fun armTick(delayMs: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, tick()!!)
    }

    override fun cancelTick() {
        tick(PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
    }

    override fun schedulePeriodic() = LocationWork.schedule(app)

    override fun cancelPeriodic() = LocationWork.cancel(app)

    /** One PendingIntent (same request code and flags): arming again replaces the pending tick (ruling L6). */
    private fun tick(mode: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        app,
        REQUEST_TICK,
        Intent(app, LocationTickReceiver::class.java),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "KinsightLocation"
        const val REQUEST_TICK = 41
    }
}

/** Spec §8.1 «В зоне: 30–60 мин»: a 30-minute period (Doze windows stretch it towards 60). */
object LocationWork {
    const val PERIODIC = "kinsight-location-periodic"
    private const val PERIOD_MINUTES = 30L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

/** Thin: the engine decides. A failure is logged by class name (never a coordinate) and waits for the next period. */
class LocationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        LocationGraph.engine(applicationContext)?.onTick(Tick.PERIODIC)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("KinsightLocation", "location check failed: ${e.javaClass.simpleName}")
        Result.success()
    }
}
