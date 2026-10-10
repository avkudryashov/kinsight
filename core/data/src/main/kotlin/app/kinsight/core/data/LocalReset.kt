package app.kinsight.core.data

import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.wipeAll
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsagePipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local wipes (spec §6.2, §9.5). Order, by the lock order controlLock → stateLock → pipeline lock:
 * 1. the sync service stops (so no session writes into the wiped database);
 * 2. [forgetGroupFiles] (in the app SyncGraph.forgetGroup: group file and transport state under the transport state
 *    lock), outside the pipeline lock: once the group file is gone the pipeline has no target and publishes nothing;
 * 3. under the usage pipeline lock: database tables, usage settings; for a new identity also the keys
 *    ([wipeIdentity], blocking, on [io], after the data). Nothing in there takes the transport state lock.
 */
class LocalReset(
    private val db: KinsightDatabase,
    private val pipeline: UsagePipeline,
    private val forgetGroupFiles: suspend () -> Unit,
    private val settings: SettingsStore,
    private val prefs: AppPrefs,
    private val sync: SyncControl,
    private val wipeIdentity: () -> Unit,
    private val snapshots: Snapshots,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Leaving or being removed: no trace of the group; the identity and the device name stay. */
    suspend fun forgetGroup() {
        sync.stopAndWait()
        forgetGroupFiles()
        pipeline.exclusive { wipeData() }
        prefs.resetForNewGroup()
        snapshots.invalidate()
    }

    /** «Сбросить идентичность и пересопрячь»: a new device as far as the group is concerned. */
    suspend fun resetIdentity() {
        sync.stopAndWait()
        forgetGroupFiles()
        pipeline.exclusive {
            wipeData()
            withContext(io) { wipeIdentity() }
        }
        prefs.reset()
        snapshots.invalidate()
    }

    private suspend fun wipeData() {
        db.wipeAll()
        settings.reset()
    }
}
