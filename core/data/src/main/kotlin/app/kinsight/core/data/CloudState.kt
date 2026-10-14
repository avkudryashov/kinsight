package app.kinsight.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * What the last cloud run ran into (spec §11). [UNAUTHORIZED]: the owner must sign in again; [NO_ACCESS]: a member has no
 * working token and waits for the owner; [STORAGE_FULL]: the owner's Disk is full; [NETWORK]: retried, nothing to show.
 */
enum class CloudProblem { UNAUTHORIZED, NO_ACCESS, STORAGE_FULL, NETWORK }

data class CloudState(val lastRunMs: Long? = null, val lastSyncMs: Long? = null, val problem: CloudProblem? = null)

/** The cloud worker's last run, for the cadence and the screens. Only hints live here: a corrupt file starts over. */
class CloudStateStore(private val store: DataStore<Preferences>) {
    val state: Flow<CloudState> = store.data.map { p ->
        CloudState(p[LAST_RUN], p[LAST_SYNC], p[PROBLEM]?.let { n -> CloudProblem.entries.firstOrNull { it.name == n } })
    }

    suspend fun current(): CloudState = state.first()

    suspend fun record(nowMs: Long, synced: Boolean, problem: CloudProblem?) {
        store.edit { p ->
            p[LAST_RUN] = nowMs
            if (synced) p[LAST_SYNC] = nowMs
            if (problem == null) p.remove(PROBLEM) else p[PROBLEM] = problem.name
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        const val NAME = "kinsight_cloud_state"
        private val LAST_RUN = longPreferencesKey("last_run_ms")
        private val LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val PROBLEM = stringPreferencesKey("problem")

        /** [file] must end with ".preferences_pb". */
        fun create(file: File, scope: CoroutineScope): CloudStateStore = CloudStateStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
