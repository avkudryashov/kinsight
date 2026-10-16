package app.kinsight.core.location

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
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
class AndroidCadence(context: Context) : Cadence {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override fun startService(): Boolean = try {
        app.startForegroundService(Intent(app, LocationService::class.java))
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "location service not allowed from the background") // spec §14, Android 12+ / 14+
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "location service refused: ${e.javaClass.simpleName}")
        false
    }

    override fun stopService() {
        app.stopService(Intent(app, LocationService::class.java))
    }

    override fun armTick(delayMs: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, tick())
    }

    override fun cancelTick() {
        alarms.cancel(tick())
    }

    override fun schedulePeriodic() = LocationWork.schedule(app)

    override fun cancelPeriodic() = LocationWork.cancel(app)

    private fun tick() = PendingIntent.getBroadcast(
        app,
        REQUEST_TICK,
        Intent(app, LocationTickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
