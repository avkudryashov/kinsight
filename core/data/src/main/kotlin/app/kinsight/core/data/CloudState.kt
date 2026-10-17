package app.kinsight.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 * [RENEW_ACCESS]: a phone that knew the token left or was removed: the owner signs in for a new one (spec §7.4); computed
 * from the log, never recorded.
 */
enum class CloudProblem { UNAUTHORIZED, NO_ACCESS, STORAGE_FULL, NETWORK, RENEW_ACCESS }

/**
 * [syncedGeneration]: the storage generation of the last successful sync (the Heartbeat reports it, spec §7);
 * [refreshCheckMs]: the owner's last token check (spec §6 «Продление», weekly); [tokenSinceMs]: when the owner's token
 * was last refreshed, or first checked here (rulings Q9: refreshed at 30 days of age); [ownerHeard]: when this device
 * last saw the storage owner's log grow, on its own clock (rulings Q14, for «Владелец недоступен»); [transfer]: the owner's
 * transfer invite this phone welcomed (spec §7.3), until the new phone's StorageOwner arrives and this phone leaves;
 * [olderAccessGeneration]: the newest older storage generation whose CloudAccess token this parent dealt with (taken for
 * the revoke or found in use), so a token once revoked is never taken from the log again; [heartbeatPending]: a sync
 * reached a new storage generation and its Heartbeat is not signed yet (rulings Q18), kept across a process death;
 * [deadOldToken]: the child's kept older token got a 401 from the old Disk under this mark (see CloudRunner), so it is not
 * tried again until the mark changes.
 */
data class CloudState(
    val lastRunMs: Long? = null,
    val lastSyncMs: Long? = null,
    val problem: CloudProblem? = null,
    val syncedGeneration: Long? = null,
    val refreshCheckMs: Long? = null,
    val ownerHeard: OwnerHeard? = null,
    val tokenSinceMs: Long? = null,
    val transfer: OwnerTransferRecord? = null,
    val olderAccessGeneration: Long? = null,
    val heartbeatPending: Boolean = false,
    val deadOldToken: String? = null,
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
            p[TOKEN_SINCE],
            transfer(p),
            p[OLDER_ACCESS],
            p[HEARTBEAT_PENDING] == true,
            p[DEAD_OLD_TOKEN],
        )
    }

    suspend fun current(): CloudState = state.first()

    /** A sync of a [generation] other than the recorded one also owes a Heartbeat ([CloudState.heartbeatPending]), in the same write. */
    suspend fun record(nowMs: Long, synced: Boolean, problem: CloudProblem?, generation: Long? = null) {
        store.edit { p ->
            p[LAST_RUN] = nowMs
            if (synced) {
                p[LAST_SYNC] = nowMs
                generation?.let {
                    if (p[SYNCED_GENERATION] != it) p[HEARTBEAT_PENDING] = true
                    p[SYNCED_GENERATION] = it
                }
            }
            if (problem == null) p.remove(PROBLEM) else p[PROBLEM] = problem.name
        }
    }

    /** The owner checked its token at [nowMs]; [tokenSinceMs], when given, starts the token's age (a refresh, or a first check). */
    suspend fun markRefreshCheck(nowMs: Long, tokenSinceMs: Long? = null) {
        store.edit { p ->
            p[REFRESH_CHECK] = nowMs
            tokenSinceMs?.let { p[TOKEN_SINCE] = it }
        }
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

    /** The Heartbeat owed after a new storage generation was signed. */
    suspend fun heartbeatSigned() {
        store.edit { p -> p.remove(HEARTBEAT_PENDING) }
    }

    /** The kept older token was refused under [mark]. */
    suspend fun markDeadOldToken(mark: String) {
        store.edit { p -> p[DEAD_OLD_TOKEN] = mark }
    }

    /** Final review, Important 3: the CloudAccess token of storage [generation] was dealt with (CloudRunner). */
    suspend fun markOlderAccess(generation: Long) {
        store.edit { p -> if ((p[OLDER_ACCESS] ?: 0L) < generation) p[OLDER_ACCESS] = generation }
    }

    /** Spec §7.3: this phone welcomed [r]'s target with the owner's tokens; replaces any earlier record. */
    suspend fun recordTransfer(r: OwnerTransferRecord) {
        store.edit { p ->
            p[TRANSFER_GROUP] = r.groupHex
            p[TRANSFER_TO] = r.target.hex
            p[TRANSFER_TOKEN_ID] = r.tokenIdHex
            p[TRANSFER_GENERATION] = r.generation
            p[TRANSFER_UNTIL] = r.untilMs
            p[TRANSFER_REFUSED] = r.refused
        }
    }

    /** The new phone said it did not take the role: [r] stays for its safety (it expires by itself), marked refused. */
    suspend fun markTransferRefused(r: OwnerTransferRecord) {
        store.edit { p ->
            if (transfer(p)?.let { it.groupHex == r.groupHex && it.target == r.target && it.generation == r.generation } == true) {
                p[TRANSFER_REFUSED] = true
            }
        }
    }

    /** Drops the record, only when it is still [r] (a newer invite may have replaced it). */
    suspend fun clearTransfer(r: OwnerTransferRecord) {
        store.edit { p ->
            if (transfer(p)?.let { it.groupHex == r.groupHex && it.target == r.target && it.generation == r.generation } == true) {
                listOf(TRANSFER_GROUP, TRANSFER_TO, TRANSFER_TOKEN_ID).forEach { p.remove(it) }
                p.remove(TRANSFER_GENERATION)
                p.remove(TRANSFER_UNTIL)
                p.remove(TRANSFER_REFUSED)
            }
        }
    }

    private fun transfer(p: Preferences): OwnerTransferRecord? {
        val to = p[TRANSFER_TO]?.let { runCatching { DeviceId(it) }.getOrNull() } ?: return null
        return OwnerTransferRecord(
            p[TRANSFER_GROUP] ?: return null, to, p[TRANSFER_TOKEN_ID] ?: return null, p[TRANSFER_GENERATION] ?: return null,
            p[TRANSFER_UNTIL] ?: return null, refused = p[TRANSFER_REFUSED] == true,
        )
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
        private val TOKEN_SINCE = longPreferencesKey("token_since_ms")
        private val TRANSFER_GROUP = stringPreferencesKey("transfer_group")
        private val TRANSFER_TO = stringPreferencesKey("transfer_to")
        private val TRANSFER_TOKEN_ID = stringPreferencesKey("transfer_token_id")
        private val TRANSFER_GENERATION = longPreferencesKey("transfer_generation")
        private val TRANSFER_UNTIL = longPreferencesKey("transfer_until_ms")
        private val TRANSFER_REFUSED = booleanPreferencesKey("transfer_refused")
        private val OLDER_ACCESS = longPreferencesKey("older_access_generation")
        private val HEARTBEAT_PENDING = booleanPreferencesKey("heartbeat_pending")
        private val DEAD_OLD_TOKEN = stringPreferencesKey("dead_old_token")

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
