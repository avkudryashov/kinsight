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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.kinsight.core.maps.SyncRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Offline map spec §3 and §7 schedules: a check (no network) when new points may have come and once a day; one download
 * run over any network but roaming, retried with backoff, that fetches every missing block.
 */
object MapWork {
    const val CHECK = "kinsight-map-check"
    const val SYNC = "kinsight-map-sync"
    const val DAILY = "kinsight-map-daily"

    /** Spec §3 «проверка раз в сутки»: week-old blocks go, missing blocks are queued. Not right at start-up. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MapCheckWorker>(1, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A cloud run or a Bluetooth session brought entries of others: a child's new points, maybe. A check now. */
    fun checkSoon(context: Context): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MapCheckWorker>().build())

    /** [checkSoon] only when [wanted] (a parent's phone with the cloud on): a child's phone queues nothing. */
    suspend fun checkIfWanted(context: Context, wanted: suspend () -> Boolean): Boolean {
        if (!wanted()) return false
        checkSoon(context).await()
        return true
    }

    /** The trigger of new entries of others (a cloud run, a Bluetooth session), off the caller's thread. */
    fun checkSoonIfParent(context: Context): Job {
        val graph = AppGraph.get(context)
        return graph.scope.launch {
            try {
                checkIfWanted(context, graph.maps::wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "map check trigger failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Spec §7: any network but roaming (Android's Data Saver still keeps background mobile data closed), with retry.
     * KEEP: a pending run reads the ledger when it starts and fetches every missing block.
     */
    fun syncSoon(context: Context): Operation {
        val request = OneTimeWorkRequestBuilder<MapSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_ROAMING).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DAILY)
        wm.cancelUniqueWork(CHECK)
        wm.cancelUniqueWork(SYNC)
    }

    /** A block download runs now: «Карта улиц загружается» (spec §4). */
    fun downloading(context: Context): Flow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SYNC)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .distinctUntilChanged()

    fun resultOf(run: SyncRun): ListenableWorker.Result =
        if (run == SyncRun.RETRY) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

    private const val TAG = "KinsightMap"
}

/** Thin: MapBlocks.check; a needed download is queued for the network. A failure waits for the next point or the daily check. */
class MapCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (AppGraph.get(applicationContext).maps.check()) MapWork.syncSoon(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "map check failed: ${e.javaClass.simpleName}")
        Result.success()
    }

    private companion object {
        const val TAG = "KinsightMap"
    }
}

/** Thin: MapBlocks.run off roaming; a failed tile or TileJSON is retried with backoff. */
class MapSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        MapWork.resultOf(AppGraph.get(applicationContext).maps.run())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "map download failed: ${e.javaClass.simpleName}")
        Result.retry()
    }

    private companion object {
        const val TAG = "KinsightMap"
    }
}
