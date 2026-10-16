package app.kinsight.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A proximity alert of one zone (spec §8.1); the data `kinsight-zone:<hex>` names it. */
class ZoneAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = zoneOf(intent) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        within { LocationGraph.launch(context, "zone alert") { it.onProximity(id, entering) } }
    }

    companion object {
        const val SCHEME = "kinsight-zone"
        private val HEX = Regex("^[0-9a-f]{32}$")

        fun uri(zoneId: String): Uri = Uri.fromParts(SCHEME, zoneId, null)

        fun zoneOf(intent: Intent): String? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart?.takeIf { HEX.matches(it) }
    }
}

/** The 5-minute tick outside the zones (spec §8.1). */
class LocationTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        within { LocationGraph.launch(context, "tick") { it.onTick(Tick.OUTSIDE) } }
    }
}

/** Alerts and alarms are gone after a reboot or an update: everything is registered again. */
class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        within { LocationGraph.refresh(context, rearm = true) }
    }
}

/**
 * A background broadcast has 60 s before Android treats it as an ANR. The engine's work (a fix of up to 20 s, perhaps
 * after waiting on the engine lock behind another fix, then signing) keeps running, but the broadcast is released after
 * [RECEIVER_BUDGET_MS] at the latest; the work is never cancelled halfway, so the tracker state stays consistent.
 */
internal object Broadcasts {
    const val RECEIVER_BUDGET_MS = 30_000L

    /** Calls [finish] exactly once: when [job] completes or after [budgetMs], whichever comes first. */
    fun finishWithin(scope: CoroutineScope, job: Job, budgetMs: Long, finish: () -> Unit) {
        val done = AtomicBoolean()
        val once = { if (done.compareAndSet(false, true)) finish() }
        val timer = scope.launch {
            delay(budgetMs)
            once()
        }
        job.invokeOnCompletion {
            timer.cancel()
            once()
        }
    }
}

private fun BroadcastReceiver.within(start: () -> Job) {
    val pending = goAsync()
    Broadcasts.finishWithin(LocationGraph.scope, start(), Broadcasts.RECEIVER_BUDGET_MS) { pending.finish() }
}
