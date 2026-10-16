package app.kinsight.core.location

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide wiring: one engine, one tracker store; the app installs [host] at start (core/data FamilyLocation). */
object LocationGraph {
    private const val TAG = "KinsightLocation"

    /** Until the app sets it, nothing is tracked. */
    @Volatile
    var host: (Context) -> LocationHost? = { null }

    @Volatile
    private var engine: LocationEngine? = null

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun engine(context: Context): LocationEngine? {
        engine?.let { return it }
        val app = context.applicationContext
        val h = host(app) ?: return null
        return synchronized(this) {
            engine ?: LocationEngine(
                h,
                DataStoreTrackerStore.create(app.preferencesDataStoreFile(DataStoreTrackerStore.NAME), scope),
                AndroidFixSource(app),
                AndroidProximityAlerts(app),
                AndroidCadence(app),
            ).also { engine = it }
        }
    }

    /** Runs [block] on the engine; a failure is logged by class name only (never a coordinate). */
    fun launch(context: Context, what: String, block: suspend (LocationEngine) -> Unit): Job = scope.launch {
        try {
            engine(context)?.let { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    fun refresh(context: Context, rearm: Boolean = false): Job = launch(context, "refresh") { it.refresh(rearm) }

    /**
     * LocalReset, under the cloud runLock: every alert, the service, the tick, the check and the state go. Without a
     * host there is no engine and nothing is undone, so the app installs [host] before a reset can run.
     */
    suspend fun forget(context: Context) {
        engine(context)?.forget()
    }

    /** One fix on a parent's phone for the zone editor (foreground permission only). */
    suspend fun fixOnce(context: Context): Fix? = AndroidFixSource(context).fix(LocationEngine.FIX_TIMEOUT_MS)
}
