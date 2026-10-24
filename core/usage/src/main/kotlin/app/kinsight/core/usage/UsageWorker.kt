package app.kinsight.core.usage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Thin: all logic lives in UsagePipeline. A failed run is logged and retried by the next schedule. */
class UsageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(UsageWork.KEY_FORCE, false)
        val rollover = inputData.getBoolean(UsageWork.KEY_ROLLOVER, false)
        try {
            UsageGraph.beforeRun(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "before-run check failed: ${e.javaClass.simpleName}")
        }
        return try {
            val report = UsageGraph.pipeline(applicationContext).collectAndPublish(force)
            Log.i(TAG, "usage run force=$force rollover=$rollover: $report")
            // A failed rollover run chains nothing (an appended child would fail with it); the next hourly run re-arms.
            UsageWork.scheduleRollover(applicationContext, UsageWork.rolloverPolicyAfterRun(rollover))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "usage run failed", e)
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "KinsightUsage"
    }
}
