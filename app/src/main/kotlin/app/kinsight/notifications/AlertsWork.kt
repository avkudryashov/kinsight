package app.kinsight.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kinsight.core.data.AlertInput
import app.kinsight.core.data.AlertMarkers
import app.kinsight.core.data.AlertPlan
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupSnapshot
import app.kinsight.core.data.Role
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.SyncGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * One alerts pass: plan, post, then remember what was said (at least once: a crash between the two repeats an alert,
 * never loses one); once a day apply retention to the local log (spec §4.7).
 *
 * Passes are serialised by [lock] (the hourly and the after-session work are two unique works and may overlap), so
 * two passes never read the same markers and both post. Lock order: [lock], then SyncGraph's stateLock, then the
 * pipeline lock; each of those is taken and released inside the calls below, never held around them.
 */
object AlertsRun {
    private val lock = Mutex()

    suspend fun run(context: Context): Unit = serialized {
        SyncGraph.onNearbyPermission(context, BlePermissions.missing(context).isEmpty())
        val g = AppGraph.get(context)
        val snapshot = g.snapshots.load(g.transport.peerSeen()) as? GroupSnapshot.Ready ?: return@serialized
        // A removed device has no self among the members: AlertPlanner.input would throw.
        if (snapshot.removed) return@serialized
        val prefs = g.prefs.current()
        val nowMs = System.currentTimeMillis()
        val input = clampJoined(AlertPlanner.input(snapshot, prefs, g.prefs.alertMarkers(), nowMs, ZoneId.systemDefault()))
        AlertChannels.ensure(context)
        // setAlertMarkers is one DataStore edit: the markers are replaced whole or not at all.
        deliver(context, input, AlertPlanner.plan(input)) { g.prefs.setAlertMarkers(it) }
        val today = LocalDate.now().toString()
        if (prefs.lastPruneDate != today) {
            g.pruner.prune()
            g.prefs.setLastPruneDate(today)
        }
    }

    internal suspend fun <T> serialized(block: suspend () -> T): T = lock.withLock { block() }

    /** Posts every alert first, then stores the markers [markersToStore] allows. A throwing post stores nothing. */
    internal suspend fun deliver(context: Context, input: AlertInput, plan: AlertPlan, store: suspend (AlertMarkers) -> Unit) {
        var delivered = AlertNotifier.canPost(context)
        if (delivered) {
            for (alert in plan.alerts) {
                if (!AlertNotifier.post(context, alert)) {
                    delivered = false
                    break
                }
            }
        }
        markersToStore(input, plan, delivered)?.let { store(it) }
    }

    /**
     * What may be remembered after a pass. Posted (or nothing to say): the plan's markers. Not posted (notifications
     * off or POST_NOTIFICATIONS denied): nothing, so the alerts come once notifications are allowed; except a
     * parent's first pass, which still baselines the history (the planner marks it seen on the first pass, and a
     * later first pass would swallow the events of the silent time) without saying its no-contact or summary news.
     * A child's first-pass baseline is its inviter, which does not move with time: nothing is stored.
     */
    internal fun markersToStore(input: AlertInput, plan: AlertPlan, delivered: Boolean): AlertMarkers? = when {
        delivered || plan.alerts.isEmpty() -> plan.markers
        input.self.role != Role.CHILD && !input.markers.initialized ->
            input.markers.copy(notifiedSeq = plan.markers.notifiedSeq, initialized = true)
        else -> null
    }

    /** A member added by a phone whose clock ran ahead: its no-contact time counts from now, not from the future. */
    internal fun clampJoined(input: AlertInput): AlertInput =
        input.copy(joinedMs = input.joinedMs.mapValues { minOf(it.value, input.nowMs) })
}

/** Runs off the main thread (CoroutineWorker, Dispatchers.Default); WorkManager defers it in Doze. */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AlertsRun.run(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("KinsightAlerts", "alerts run failed", e)
        Result.success() // the next hourly run tries again
    }
}

object AlertsWork {
    const val PERIODIC = "kinsight-alerts-hourly"
    const val SOON = "kinsight-alerts-soon"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AlertsWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    /**
     * After a sync session: new events may have arrived. KEEP: one pending run covers several sessions; a session
     * that ends while a run is already past its snapshot is picked up by the next session or the hourly run.
     */
    fun runSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AlertsWorker>().build())
    }
}
