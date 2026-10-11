package app.kinsight

import android.app.Application
import android.util.Log
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.IntegrityFastSync
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.usage.UsageGraph
import app.kinsight.core.usage.UsageWork
import app.kinsight.feature.widget.KinsightWidget
import app.kinsight.feature.widget.WidgetLinks
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
 * start (with or without a group: the baseline), a revoked «Nearby devices», and the one-time move of a parent's local
 * category overrides into the shared log (stage 5c). Each check runs on its own, so one
 * failing does not skip the others. Open only so the JVM tests can start WorkManager first (TestKinsightApplication).
 */
open class KinsightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncGraph.install(this)
        IntegrityFastSync.install(this)
        UsageWork.schedule(this)
        AlertsWork.schedule(this)
        KinsightWidget.links = WidgetLinks(open = MainActivity::open, openDevice = MainActivity::openDevice)
        SyncGraph.onSessionFinished = { AlertsWork.runSoon(it) }
        SyncGraph.onboardingDone = { AppGraph.get(it).prefs.current().onboardingDone }
        UsageAccessWatcher.register(this)
        val app = this
        // A reset or leave wipes after MainActivity.onStop may have redrawn the widget: redraw once the wipe ends.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            attempt("widget wipe watch") {
                var previous: ResetStatus = ResetStatus.Idle
                AppGraph.get(app).reset.status.collect { s ->
                    if (KinsightWidget.wipeEnded(previous, s)) KinsightWidget.update(app)
                    previous = s
                }
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            attempt("reset recovery") { AppGraph.get(app).reset.finishInterruptedReset() }
            attempt("sharing reconcile") { UsageGraph.pipeline(app).reconcileSharing() }
            attempt("join recovery") { AppGraph.get(app).pairing.finishInterruptedJoin() }
            attempt("force stops") { SyncGraph.checkForceStops(app) }
            attempt("nearby permission") { SyncGraph.onNearbyPermission(app, BlePermissions.missing(app).isEmpty()) }
            // Stage 5c: a parent's local category choices of 5a become shared entries, once (idempotent, crash-safe).
            attempt("category migration") { AppGraph.get(app).usage.migrateLocalCategories() }
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
