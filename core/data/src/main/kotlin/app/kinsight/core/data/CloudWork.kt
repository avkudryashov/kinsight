package app.kinsight.core.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.YandexDisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** Process-wide cloud wiring next to AppGraph: the build's Yandex client, the one run lock, the debug Disk switch. */
object CloudGraph {
    /** Set by the app at start from BuildConfig; [CloudConfig.NONE] in a build without a Yandex client (no cloud offered). */
    @Volatile
    var config: CloudConfig = CloudConfig.NONE

    /**
     * The one cloud run lock of the process (R15): CloudSecrets is built with it, and the runner and the local wipe take
     * it from there, so a run never writes an old group's keys back after a wipe (R14).
     */
    val runLock = Mutex()

    /** Debug builds only (CLOUD_FAKE_DISK): an in-memory Disk instead of Yandex, for emulator runs. */
    @Volatile
    var fakeDisk: InMemoryDisk? = null

    fun disk(accessToken: suspend () -> String): DiskApi = fakeDisk ?: YandexDisk(accessToken)
}

/**
 * Spec §9 schedules: one periodic job every 15 minutes with a network (a child's runs skip when nothing is new, see
 * CloudRunner), a run now when the app opens or a token arrives. Scheduled only while the group has a storage owner.
 * Network trouble, 5xx and 429 retry with exponential backoff (spec §11).
 */
object CloudWork {
    const val PERIODIC = "kinsight-cloud-periodic"
    const val NOW = "kinsight-cloud-now"
    const val KEY_TRIGGER = "trigger"
    private const val TAG = "KinsightCloud"

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_TRIGGER to CloudTrigger.PERIODIC.name))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudWorker>()
            .setConstraints(network())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to CloudTrigger.NOW.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
    }

    /** App start and after every Bluetooth session (it may have brought a storage owner): the periodic job, if the cloud is on. */
    fun keepScheduled(context: Context) = whenOn(context) { schedule(context) }

    /** The app was opened (spec §9: a parent reads at once) or a token arrived: the periodic job and a run now. */
    fun runSoon(context: Context) = whenOn(context) {
        schedule(context)
        runNow(context)
    }

    fun resultOf(run: CloudRun): ListenableWorker.Result =
        if (run == CloudRun.RETRY) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

    private fun whenOn(context: Context, block: () -> Unit) {
        val app = context.applicationContext
        val g = AppGraph.get(app)
        g.scope.launch {
            try {
                if (g.cloud.isOn()) block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cloud schedule check failed", e)
            }
        }
    }
}

/** Thin: all logic lives in CloudRunner. A run that finds the cloud off cancels the schedule. */
class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(CloudWork.KEY_TRIGGER)?.let { n -> CloudTrigger.entries.firstOrNull { it.name == n } } ?: CloudTrigger.PERIODIC
        return try {
            val run = AppGraph.get(applicationContext).cloud.run(trigger)
            Log.i(TAG, "cloud run $trigger: $run")
            if (run == CloudRun.OFF) CloudWork.cancel(applicationContext)
            CloudWork.resultOf(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class name only: a Disk or OAuth failure message is never trusted not to carry a token.
            Log.w(TAG, "cloud run failed: ${e.javaClass.simpleName}")
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "KinsightCloud"
    }
}
