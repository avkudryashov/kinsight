package app.kinsight

import android.app.Application
import android.util.Log
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.CloudConfig
import app.kinsight.core.data.CloudGraph
import app.kinsight.core.data.CloudSessionHandoff
import app.kinsight.core.data.CloudWork
import app.kinsight.core.data.IntegrityFastSync
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.location.LocationGraph
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
 * collection, the integrity alerts, the cloud job while the cloud is on (spec §9), and the checks only a fresh process can make: the «share» switch reconciled with
 * the last signed SHARING_* (spec §7), an identity reset cut short (finished first), a join interrupted after the welcome finished, force stops since the last
 * start (with or without a group: the baseline), a revoked «Nearby devices», and the one-time move of a parent's local
 * category overrides into the shared log (stage 5c), and the child's location tracking (registered again at every start). Each check runs on its own, so one
 * failing does not skip the others. Open only so the JVM tests can start WorkManager first (TestKinsightApplication).
 */
open class KinsightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CloudGraph.config = CloudConfig(BuildConfig.YANDEX_CLIENT_ID, BuildConfig.YANDEX_CLIENT_SECRET, BuildConfig.YANDEX_REDIRECT_URI)
        SyncGraph.install(this)
        SyncGraph.cloudHandoff = { ctx, group, store, keys -> CloudSessionHandoff(AppGraph.get(ctx).cloudSecrets, group, store, keys.deviceId) }
        SyncGraph.cloudGeneration = { AppGraph.get(it).cloudState.current().syncedGeneration ?: 0L }
        // Spec §8: the child's tracking reads and signs through the family (core/data FamilyLocation).
        LocationGraph.host = { AppGraph.get(it).familyLocation }
        // Emulator finding: access given back in the system settings is found by the 30-minute check (app closed) and
        // whenever the app comes to the foreground; a check that sees the change refreshes the tracking (AppGraph).
        LocationGraph.stateCheck = { AppGraph.get(it).launchLocationCheck() }
        ForegroundCheck.register(this) {
            AppGraph.get(it).launchLocationCheck()
            LocationGraph.refresh(it)
        }
        IntegrityFastSync.install(this)
        UsageWork.schedule(this)
        CloudWork.keepScheduled(this)
        AlertsWork.schedule(this)
        KinsightWidget.links = WidgetLinks(open = MainActivity::open, openDevice = MainActivity::openDevice)
        // Y4 final review: a cloud run that brought entries of others (a zone exit) gets its alerts pass at once (spec §9).
        CloudWork.onReceived = { AlertsWork.runSoon(it) }
        SyncGraph.onSessionFinished = {
            AlertsWork.runSoon(it)
            CloudWork.keepScheduled(it)
            LocationGraph.refresh(it) // a session may have brought new zones
            // Rulings Q14: the session may have brought the storage owner's new entries.
            val graph = AppGraph.get(it)
            graph.scope.launch { attempt("owner heard") { graph.cloud.noteOwnerHeard() } }
        }
        SyncGraph.onboardingDone = { AppGraph.get(it).prefs.current().onboardingDone }
        UsageAccessWatcher.register(this)
        LocationStateWatcher.register(this)
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
            // Alerts and the tick do not survive a force stop: a fresh process registers them again.
            // Launched, not awaited: a first fix (up to 20 s) must not hold back the checks below (Y4 review Minor 3).
            attempt("location") { LocationGraph.refresh(app, rearm = true) }
            attempt("location state") { AppGraph.get(app).launchLocationCheck() } // launched too: it may collect usage
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
