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
import androidx.work.Operation
import androidx.work.await
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.first
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.YandexDisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    const val FOLLOW = "kinsight-cloud-follow"
    const val KEY_FOLLOW = "follow"

    /** Spec §9: while a child is outside its zones, a parent reads every 5 minutes. */
    const val FOLLOW_MINUTES = 5L
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
    fun runNow(context: Context, policy: ExistingWorkPolicy): Operation {
        val request = OneTimeWorkRequestBuilder<CloudWorker>()
            .setConstraints(network())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to CloudTrigger.NOW.name))
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(FOLLOW)
    }

    /** The follow-up run queues its successor behind itself; any other run leaves a waiting one alone. */
    fun followPolicy(fromFollow: Boolean): ExistingWorkPolicy =
        if (fromFollow) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP

    /**
     * Spec §9 «родитель может читать чаще, со своей службой и уведомлением»: one run [FOLLOW_MINUTES] from now. The
     * parent's own service and notification are the Bluetooth SyncService every phone of a group already runs.
     */
    fun follow(context: Context, fromFollow: Boolean) {
        val request = OneTimeWorkRequestBuilder<CloudWorker>()
            .setInitialDelay(FOLLOW_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to CloudTrigger.NOW.name, KEY_FOLLOW to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FOLLOW, followPolicy(fromFollow), request)
    }

    /**
     * Spec §8.1: a zone event or an outside point goes to the Disk at once (after a run in flight). Only enqueues work
     * (rulings L8): the location engine calls it and must never wait for, or run, the cloud itself.
     */
    fun uploadSoon(context: Context): Job = whenOn(context) { upload(context) }

    /** What was just signed is uploaded by a run of its own after a run in flight, never dropped as a duplicate. */
    val UPLOAD_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

    /** One upload decision at a time: two ticks never both see no pending run and both queue one. */
    private val uploadLock = Mutex()

    /**
     * What [uploadSoon] does once it knows the cloud is on (Y4 final review Important 2): at most one pending run. A run
     * not started yet (ENQUEUED, waiting on its network or a backoff, or BLOCKED behind one in flight) reads everything
     * signed by the time it starts, so it is kept; only a RUNNING one gets one run of its own appended behind it.
     */
    suspend fun upload(context: Context) {
        uploadLock.withLock {
            val states = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW).first().map { it.state }
            // Awaited inside the lock: the next decision reads the WorkInfo this enqueue wrote.
            uploadPolicy(states)?.let { runNow(context, it).await() }
        }
    }

    /** The policy of a new upload run given the states of the unique [NOW] work, or null to keep the pending one. */
    fun uploadPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy? =
        if (states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }) null else UPLOAD_POLICY

    /**
     * Set by the app (next to SyncGraph.onSessionFinished): an alerts pass soon. Asked after a cloud run that brought
     * entries of others, so a zone exit read from the Disk is told at the cloud cadence, not the hourly pass (spec §9).
     */
    @Volatile
    var onReceived: ((Context) -> Unit)? = null

    /**
     * Y4 final review Important 1: a run that brought entries of others asks for an alerts pass; a full Disk still reads
     * the others (STORAGE_FULL with entries received), so it asks too.
     */
    fun alertsNeeded(outcome: CloudRunOutcome): Boolean = outcome.received > 0

    /** Spec §9: a follow-up is queued only after a run that read the Disk, while a child is outside ([needed] is asked then). */
    suspend fun followNext(run: CloudRun, needed: suspend () -> Boolean): Boolean = run == CloudRun.SYNCED && needed()

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
    private fun whenOn(context: Context, block: suspend () -> Unit): Job {
        val g = AppGraph.get(context.applicationContext)
        return enqueueIfOn(g.scope, g.cloud::isOn, block)
    }

    /**
     * [whenOn] without the Context: launched on [scope], so the caller never waits; [isOn] (CloudRunner.isOn) reads the
     * group and membership only and takes no runLock, so a cloud run in flight never holds the enqueue back (rulings L8).
     */
    internal fun enqueueIfOn(scope: CoroutineScope, isOn: suspend () -> Boolean, block: suspend () -> Unit): Job =
        scope.launch {
            try {
                if (isOn()) block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cloud schedule check failed", e)
            }
        }
}

/** Thin: all logic lives in CloudRunner. A run that finds the cloud off cancels the schedule. */
class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(CloudWork.KEY_TRIGGER)?.let { n -> CloudTrigger.entries.firstOrNull { it.name == n } } ?: CloudTrigger.PERIODIC
        val follow = inputData.getBoolean(CloudWork.KEY_FOLLOW, false)
        return CloudWork.outcome {
            val g = AppGraph.get(applicationContext)
            // Spec §10, before the run and never inside its runLock: what the child turned off is signed now and goes up
            // with this run.
            attempt("location state") { g.checkLocationState() }
            // A cloud child's hourly Heartbeat (spec §10), signed before the run so this run uploads it. Outside runLock
            // (it takes stateLock and the pipeline lock only), so the run's single runLock hold is never re-entered.
            attempt("heartbeat") { SyncGraph.emitHeartbeatIfDue(applicationContext) }
            val outcome = g.cloud.runCounted(trigger)
            val run = outcome.run
            Log.i(TAG, "cloud run $trigger: $run")
            // Entries of others came in (a zone exit above all): an alerts pass now, like after a Bluetooth session.
            if (CloudWork.alertsNeeded(outcome)) CloudWork.onReceived?.invoke(applicationContext)
            if (run == CloudRun.OFF) CloudWork.cancel(applicationContext)
            // After the run (never inside its runLock): a child takes the zones this run brought (spec §8.1)...
            LocationGraph.refresh(applicationContext)
            // ...and a parent keeps reading while a child is outside (spec §9).
            if (CloudWork.followNext(run, g.familyLocation::followNeeded)) CloudWork.follow(applicationContext, fromFollow = follow)
            run
        }
    }

    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "KinsightCloud"
    }
}
