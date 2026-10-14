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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** Process-wide cloud wiring next to AppGraph: the build's Yandex client, the one run lock, the debug Disk switch. */
object CloudGraph {
    /** Set by the app at start from BuildConfig; [CloudConfig.NONE] in a build without a Yandex client (no cloud offered). */
    @Volatile
    var config: CloudConfig = CloudConfig.NONE

    /**
     * The one cloud run lock of the process (R15): CloudSecrets is built with it, and the runner, the sign-in and the
     * local wipe take it from there, so neither a run nor a sign-in writes an old group's keys back after a wipe (R14).
     */
    val runLock = Mutex()

    /**
     * Debug builds only (CLOUD_FAKE_DISK): an in-memory Disk instead of Yandex, for emulator runs. Set only through
     * CloudDebugDisk, which exists in the debug source set alone, so a release build has no way to switch it on.
     */
    @Volatile
    internal var fakeDisk: InMemoryDisk? = null

    fun disk(accessToken: suspend () -> String): DiskApi = fakeDisk ?: YandexDisk(accessToken)
}

/**
 * Spec §9 schedules: one periodic job every 15 minutes with a network (a child's runs skip when nothing is new, see
 * CloudRunner), a run now when the app opens or a token arrives. Scheduled only while the group has a storage owner.
 * Network trouble, 5xx and 429 retry with exponential backoff (spec §11), and so does an unexpected failure (rulings Q1).
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

    /**
     * A run now. [policy] [TOKEN_POLICY] (a token arrived) queues one more run after a run in flight; REPLACE (the app was
     * opened) replaces a run waiting in a long backoff, so the parent reads at once (spec §9).
     */
    fun runNow(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<CloudWorker>()
            .setConstraints(network())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to CloudTrigger.NOW.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
    }

    /** App start and after every Bluetooth session (it may have brought a storage owner): the periodic job, if the cloud is on. */
    fun keepScheduled(context: Context): Job = whenOn(context) { schedule(context) }

    /**
     * A run in flight may have read the old token (or none) and end in a §11 error after the new one is stored: a token
     * arriving always gets a run of its own after it (APPEND_OR_REPLACE), never one dropped as a duplicate (final review).
     */
    val TOKEN_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

    /** A token arrived (sign-in, pairing welcome, Bluetooth hand-off): the periodic job and a run after any in flight. */
    fun runSoon(context: Context): Job = whenOn(context) { afterToken(context) }

    /** What [runSoon] does once it knows the cloud is on. */
    fun afterToken(context: Context) {
        schedule(context)
        runNow(context, TOKEN_POLICY)
    }

    /** The app was opened (spec §9: a parent reads at once): the periodic job and a fresh run now. */
    fun opened(context: Context): Job = whenOn(context) {
        schedule(context)
        runNow(context, ExistingWorkPolicy.REPLACE)
    }

    fun resultOf(run: CloudRun): ListenableWorker.Result =
        if (run == CloudRun.RETRY) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

    /**
     * The worker's result of [run]. Rulings Q1: an unexpected failure is retried, never a failure(), so a run queued with
     * APPEND_OR_REPLACE behind this one (a token arrived) is not dropped with it.
     */
    suspend fun outcome(run: suspend () -> CloudRun): ListenableWorker.Result = try {
        resultOf(run())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Class name only: a Disk or OAuth failure message is never trusted not to carry a token.
        Log.w(TAG, "cloud run failed: ${e.javaClass.simpleName}")
        ListenableWorker.Result.retry()
    }

    /** The returned job lets a test wait for the check; callers do not need it. */
    private fun whenOn(context: Context, block: () -> Unit): Job {
        val app = context.applicationContext
        val g = AppGraph.get(app)
        return g.scope.launch {
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
        return CloudWork.outcome {
            val run = AppGraph.get(applicationContext).cloud.run(trigger)
            Log.i(TAG, "cloud run $trigger: $run")
            if (run == CloudRun.OFF) CloudWork.cancel(applicationContext)
            run
        }
    }

    private companion object {
        const val TAG = "KinsightCloud"
    }
}
