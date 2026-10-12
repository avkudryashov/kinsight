package app.kinsight

import android.app.AppOpsManager
import android.content.Context
import app.kinsight.core.usage.UsageWork
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec §7 (stage 4 task): a change of usage access is collected at once, so USAGE_ACCESS_REVOKED / RESTORED reach the
 * queue without waiting for the hourly run. The event comes from the collector inside the usage pipeline, which is
 * gated like queueEvent (only a consenting child collects; a parent gets NOT_ALLOWED and writes nothing) and commits
 * the event together with the collector's "access lost" state, so the state moves only when the event is kept.
 * The callback only enqueues work (binder thread, nothing heavy). Lives as long as the process; registers once.
 */
object UsageAccessWatcher {
    private val default = Watch { UsageWork.collectNow(it, force = false) }

    fun register(context: Context) = default.register(context)

    class Watch(private val onChange: (Context) -> Unit) {
        private val registered = AtomicBoolean(false)

        fun register(context: Context) {
            if (!registered.compareAndSet(false, true)) return
            val app = context.applicationContext
            app.getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.packageName) { op, pkg ->
                if (pkg == app.packageName && (op == null || op == AppOpsManager.OPSTR_GET_USAGE_STATS)) onChange(app)
            }
        }
    }
}
