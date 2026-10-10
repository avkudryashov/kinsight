package app.kinsight.core.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings a child's own integrity news to a parent in range within seconds instead of after the sync cooldown (phones,
 * 2026-10-10: a pause took 4 min 40 s). Every own StateEvent the pipeline signs or queues that a parent alerts on
 * ([KINDS]: AlertPlanner.INTEGRITY_KINDS, plus the closers in AlertPlanner.CLOSERS, SHARING_RESUMED and
 * NOTIFICATION_SHOWN, which clear the parent's open notification) asks for one fast-mode window, the same 60 s as
 * «Sync now», at most once per [MIN_INTERVAL_MS] (a flapping switch must not keep the radio in low latency), and never
 * while the user's Stop holds. [startFast] says whether the start went out (false: no group, or the system refused
 * the service start); a start that was skipped or failed does not use up the interval. [clock] is monotonic in the app.
 */
class IntegrityFastSync(
    private val clock: () -> Long,
    private val stoppedByUser: suspend () -> Boolean,
    private val startFast: () -> Boolean,
) {
    private val mutex = Mutex()
    private var lastStartMs: Long? = null

    /** True when this event started fast mode. */
    suspend fun onOwnStateEvent(kind: StateEvent.Kind): Boolean = mutex.withLock {
        if (kind !in KINDS) return@withLock false
        val now = clock()
        val last = lastStartMs
        if (last != null && now - last < MIN_INTERVAL_MS) return@withLock false
        if (stoppedByUser()) return@withLock false
        if (!startFast()) return@withLock false
        lastStartMs = now
        true
    }

    companion object {
        const val MIN_INTERVAL_MS = 120_000L
        val KINDS: Set<StateEvent.Kind> = AlertPlanner.INTEGRITY_KINDS + AlertPlanner.CLOSERS.values
        private const val TAG = "KinsightSync"

        /**
         * From Application.onCreate. The pipeline calls the listener inside its lock, so it only hands the kind to a
         * serial lane; the stop flag read and the start run there, outside the pipeline lock (lock order: the
         * service's controlLock and stateLock come before the pipeline lock, never after it).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun install(context: Context) {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
            val fast = IntegrityFastSync(
                clock = SystemClock::elapsedRealtime,
                stoppedByUser = { SyncGraph.state(app).stoppedByUser() },
                startFast = { SyncGraph.groups(app).exists() && SyncService.requestFast(app) },
            )
            UsageGraph.ownStateEventListener = { kind ->
                if (kind in KINDS) {
                    scope.launch {
                        try {
                            if (fast.onOwnStateEvent(kind)) Log.i(TAG, "fast mode for own $kind")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "fast mode for own $kind failed", e)
                        }
                    }
                }
            }
        }
    }
}
