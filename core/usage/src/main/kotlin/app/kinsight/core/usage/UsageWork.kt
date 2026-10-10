package app.kinsight.core.usage

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Schedules (spec §7): hourly, now (time and zone broadcasts, stage 3c), and a forced run after local midnight. */
object UsageWork {
    const val PERIODIC = "kinsight-usage-hourly"
    const val NOW = "kinsight-usage-now"
    const val ROLLOVER = "kinsight-usage-rollover"
    const val KEY_FORCE = "force"
    private const val ROLLOVER_GRACE_MS = 60_000L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        scheduleRollover(context)
    }

    /** Runs the pipeline soon; queued after a run in progress rather than replacing it. */
    fun collectNow(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<UsageWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** A forced run a minute after the next local midnight; kept if one is already waiting. */
    fun scheduleRollover(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val request = OneTimeWorkRequestBuilder<UsageWorker>()
            .setInitialDelay(delayToRollover(nowMs, zone), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_FORCE to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ROLLOVER, ExistingWorkPolicy.KEEP, request)
    }

    fun delayToRollover(nowMs: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return Duration.between(now, midnight).toMillis() + ROLLOVER_GRACE_MS
    }
}
