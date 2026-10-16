package app.kinsight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import app.kinsight.core.data.AppGraph
import java.util.concurrent.atomic.AtomicReference

/**
 * Spec §10 «LocationManager.isLocationEnabled и PROVIDERS_CHANGED»: turning location off or on in the system runs the
 * location check at once. Android 8+ no longer delivers these implicit broadcasts to manifest receivers, so the receiver
 * is registered at runtime on the application context for the life of the process (the sync service keeps it alive; the
 * CloudWorker check covers the rest), which is why the process-wide one is never unregistered. The callback only
 * launches work. Registers once.
 */
object LocationStateWatcher {
    private val ACTIONS = listOf(LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION)

    private val default = Watch { AppGraph.get(it).launchLocationCheck() }

    fun register(context: Context) = default.register(context)

    class Watch(private val onChange: (Context) -> Unit) {
        private val current = AtomicReference<BroadcastReceiver?>(null)

        fun register(context: Context) {
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action in ACTIONS) onChange(app)
                }
            }
            if (!current.compareAndSet(null, receiver)) return
            val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
            try {
                // A system broadcast still reaches a NOT_EXPORTED runtime receiver; no other app can fake it.
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(receiver, filter)
                }
            } catch (e: RuntimeException) {
                current.compareAndSet(receiver, null) // a later call may try again
                throw e
            }
        }

        /** Stops watching; harmless when nothing is registered. */
        fun unregister(context: Context) {
            val receiver = current.getAndSet(null) ?: return
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already gone (not registered): nothing to release.
            }
        }
    }
}
