package app.kinsight.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.cloud.OwnerHeard
import app.kinsight.core.transport.cloud.OwnerLifecycle
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

/**
 * [syncedGeneration]: the storage generation of the last successful sync (the Heartbeat reports it, spec §7);
 * [refreshCheckMs]: the owner's last token check (spec §6 «Продление», weekly); [ownerHeard]: when this device last saw
 * the storage owner's log grow, on its own clock (rulings Q14, for «Владелец недоступен»).
 */
data class CloudState(
    val lastRunMs: Long? = null,
    val lastSyncMs: Long? = null,
    val problem: CloudProblem? = null,
    val syncedGeneration: Long? = null,
    val refreshCheckMs: Long? = null,
    val ownerHeard: OwnerHeard? = null,
) {
    /** This device's time of the last sighting of the tracked owner (rulings Q14), or null when none is tracked. */
    val ownerHeardAtMs: Long? get() = ownerHeard?.heardAtMs

    /** [ownerHeardAtMs] when [owner] is the tracked owner, else null: the input of OwnerLifecycle.ownerUnavailable. */
    fun ownerHeardFrom(owner: DeviceId): Long? = ownerHeard?.takeIf { it.owner == owner }?.heardAtMs
}

/** The cloud worker's last run, for the cadence and the screens. Only hints live here: a corrupt file starts over. */
class CloudStateStore(private val store: DataStore<Preferences>) {
    val state: Flow<CloudState> = store.data.map { p ->
        CloudState(
            p[LAST_RUN],
            p[LAST_SYNC],
            p[PROBLEM]?.let { n -> CloudProblem.entries.firstOrNull { it.name == n } },
            p[SYNCED_GENERATION],
            p[REFRESH_CHECK],
            ownerHeard(p),
        )
    }

    suspend fun current(): CloudState = state.first()

    suspend fun record(nowMs: Long, synced: Boolean, problem: CloudProblem?, generation: Long? = null) {
        store.edit { p ->
            p[LAST_RUN] = nowMs
            if (synced) {
                p[LAST_SYNC] = nowMs
                generation?.let { p[SYNCED_GENERATION] = it }
            }
            if (problem == null) p.remove(PROBLEM) else p[PROBLEM] = problem.name
        }
    }

    suspend fun markRefreshCheck(nowMs: Long) {
        store.edit { it[REFRESH_CHECK] = nowMs }
    }

    /**
     * Rulings Q14: [owner]'s newest seq in this device's entry store is [newestSeq]; a first sighting or a higher seq is
     * heard [nowMs] (OwnerLifecycle.ownerHeard). Atomic, so the cloud run and a Bluetooth session may both call it.
     */
    suspend fun observeOwner(owner: DeviceId, newestSeq: Long, nowMs: Long) {
        store.edit { p ->
            val previous = ownerHeard(p)
            val next = OwnerLifecycle.ownerHeard(previous, owner, newestSeq, nowMs)
            if (next != previous) {
                p[OWNER_ID] = next.owner.hex
                p[OWNER_SEQ] = next.seq
                p[OWNER_HEARD] = next.heardAtMs
            }
        }
    }

    private fun ownerHeard(p: Preferences): OwnerHeard? {
        val id = p[OWNER_ID]?.let { runCatching { DeviceId(it) }.getOrNull() } ?: return null
        return OwnerHeard(id, p[OWNER_SEQ] ?: return null, p[OWNER_HEARD] ?: return null)
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        const val NAME = "kinsight_cloud_state"
        private val LAST_RUN = longPreferencesKey("last_run_ms")
        private val LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val PROBLEM = stringPreferencesKey("problem")
        private val SYNCED_GENERATION = longPreferencesKey("synced_generation")
        private val REFRESH_CHECK = longPreferencesKey("refresh_check_ms")
        private val OWNER_ID = stringPreferencesKey("owner_heard_id")
        private val OWNER_SEQ = longPreferencesKey("owner_heard_seq")
        private val OWNER_HEARD = longPreferencesKey("owner_heard_ms")

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
