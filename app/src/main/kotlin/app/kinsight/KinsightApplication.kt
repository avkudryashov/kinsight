package app.kinsight

import android.app.Application
import android.util.Log
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.IntegrityFastSync
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.usage.UsageGraph
import app.kinsight.core.usage.UsageWork
import app.kinsight.notifications.AlertChannels
import app.kinsight.notifications.AlertsWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wiring at process start: the publish target and the role/consent gate (SyncGraph.install), fast mode for own
 * integrity events (IntegrityFastSync.install), the hourly usage
 * collection, the integrity alerts, and the checks only a fresh process can make: the «share» switch reconciled with
 * the last signed SHARING_* (spec §7), a join interrupted after the welcome finished, force stops since the last
 * start (with or without a group: the baseline), and a revoked «Nearby devices». Each check runs on its own, so one
 * failing does not skip the others. Open only so the JVM tests can start WorkManager first (TestKinsightApplication).
 */
open class KinsightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncGraph.install(this)
        IntegrityFastSync.install(this)
        UsageWork.schedule(this)
        AlertChannels.ensure(this)
        AlertsWork.schedule(this)
        SyncGraph.onSessionFinished = { AlertsWork.runSoon(it) }
        SyncGraph.onboardingDone = { AppGraph.get(it).prefs.current().onboardingDone }
        UsageAccessWatcher.register(this)
        val app = this
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            attempt("sharing reconcile") { UsageGraph.pipeline(app).reconcileSharing() }
            attempt("join recovery") { AppGraph.get(app).pairing.finishInterruptedJoin() }
            attempt("force stops") { SyncGraph.checkForceStops(app) }
            attempt("nearby permission") { SyncGraph.onNearbyPermission(app, BlePermissions.missing(app).isEmpty()) }
        }
    }

    private suspend fun attempt(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "start-up check failed: $what", e)
        }
    }

    private companion object {
        const val TAG = "Kinsight"
    }
}
