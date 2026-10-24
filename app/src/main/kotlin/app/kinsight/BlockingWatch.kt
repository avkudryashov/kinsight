package app.kinsight

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import app.kinsight.core.blocking.BlockingState
import app.kinsight.core.blocking.EnforcerInputs
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.BlockingStateFollow
import app.kinsight.core.data.CloudWork
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App blocking spec §5.4 on a child's phone: whether Kinsight's accessibility service is on (core/blocking BlockingState),
 * fed to core/data BlockingStateFollow, which queues BLOCKING_OFF only while the child's own rules need the service (the
 * rules EnforcerInputs enforces: a consenting member child, a BLOCK or block-on-limit rule). Checked on every
 * accessibility change (the services listener from Android 13; before it only the overall on/off one), at process start
 * and at every UsageWorker run (UsageGraph.beforeRun). A queued event is signed and uploaded at once, as a location one
 * (AppGraph.checkLocationState). The listeners only launch work and live as long as the process. Registers once.
 */
object BlockingWatch {
    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var recheck: Job? = null

    @Volatile
    private var follow: BlockingStateFollow? = null

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        UsageGraph.beforeRun = { check(it) }
        val am = app.getSystemService(AccessibilityManager::class.java) ?: return
        am.addAccessibilityStateChangeListener { launchCheck(app) }
        if (Build.VERSION.SDK_INT >= 33) am.addAccessibilityServicesStateChangeListener(app.mainExecutor) { launchCheck(app) }
    }

    /** [check] off the caller's thread; a failure is logged by class name. */
    fun launchCheck(context: Context): Job = scope.launch {
        try {
            check(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "blocking state check failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun check(context: Context) {
        val app = context.applicationContext
        val graph = AppGraph.get(app)
        val f = follow ?: synchronized(this) {
            follow ?: BlockingStateFollow(
                graph.prefs, graph.pipeline,
                rules = { EnforcerInputs.of(graph.snapshots.snapshots.first(), graph.settings.current()).rules },
            ).also { follow = it }
        }
        val enabledNow = BlockingState.serviceEnabled(app)
        if (f.onState(enabledNow)) {
            graph.pipeline.collectAndPublish()
            CloudWork.uploadSoon(app)
        }
        if (f.offPending) scheduleRecheck(app)
    }

    /** A first «off» opens nothing until seen again after the grace: one pending re-check at a time. */
    private fun scheduleRecheck(app: Context) {
        synchronized(this) {
            if (recheck?.isActive == true) return
            recheck = scope.launch {
                delay(BlockingStateFollow.OFF_GRACE_MS + 1_000)
                try {
                    check(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "blocking state re-check failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private const val TAG = "KinsightBlocking"
}
