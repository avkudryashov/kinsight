package app.kinsight.core.transport.ble

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

data class HeartbeatMark(val atMs: Long, val version: String)

/** ForceStopCheck's memory: the exits already handled, by identity, so a clock change can neither hide nor repeat one. */
data class ForceStopMarks(val seen: Set<AppExit>)

/** Small local transport state that must survive a process restart (DataStore, never synchronised). */
class TransportStateStore(private val store: DataStore<Preferences>) {
    suspend fun lastSyncMs(): Long? = store.data.first()[LAST_SYNC]

    suspend fun setLastSyncMs(ms: Long) {
        store.edit { it[LAST_SYNC] = ms }
    }

    suspend fun bluetoothOffSinceMs(): Long? = store.data.first()[BT_OFF_SINCE]

    suspend fun setBluetoothOffSinceMs(ms: Long?) {
        store.edit { if (ms == null) it.remove(BT_OFF_SINCE) else it[BT_OFF_SINCE] = ms }
    }

    suspend fun heartbeat(): HeartbeatMark? {
        val p = store.data.first()
        val at = p[HEARTBEAT_AT] ?: return null
        return HeartbeatMark(at, p[HEARTBEAT_VERSION] ?: "")
    }

    suspend fun setHeartbeat(mark: HeartbeatMark) {
        store.edit {
            it[HEARTBEAT_AT] = mark.atMs
            it[HEARTBEAT_VERSION] = mark.version
        }
    }

    /** Set by the notification's Stop action; boot and app-update starts honour it, a start from the UI clears it. */
    suspend fun stoppedByUser(): Boolean = store.data.first()[STOPPED_BY_USER] ?: false

    suspend fun setStoppedByUser(stopped: Boolean) {
        store.edit { if (stopped) it[STOPPED_BY_USER] = true else it.remove(STOPPED_BY_USER) }
    }

    val stoppedByUserFlow: Flow<Boolean> = store.data.map { it[STOPPED_BY_USER] ?: false }.distinctUntilChanged()

    /** When the user pressed Stop; null while sync runs (StopTracker). */
    suspend fun stoppedAtMs(): Long? = store.data.first()[STOPPED_AT]

    suspend fun setStoppedAtMs(ms: Long?) {
        store.edit { if (ms == null) it.remove(STOPPED_AT) else it[STOPPED_AT] = ms }
    }

    /** One transition: the Stop flag set and, when [atMs] is given, the stop time unless one is already kept. */
    suspend fun markStoppedByUser(atMs: Long?) {
        store.edit {
            it[STOPPED_BY_USER] = true
            if (atMs != null && it[STOPPED_AT] == null) it[STOPPED_AT] = atMs
        }
    }

    /** One transition: neither the Stop flag nor a stop time. */
    suspend fun clearStop() {
        store.edit {
            it.remove(STOPPED_BY_USER)
            it.remove(STOPPED_AT)
        }
    }

    /** Since when the sync notification cannot be seen; null while it can (NotificationWatch). */
    suspend fun notificationHiddenSinceMs(): Long? = store.data.first()[NOTIFICATION_HIDDEN_SINCE]

    suspend fun setNotificationHiddenSinceMs(ms: Long?) {
        store.edit { if (ms == null) it.remove(NOTIFICATION_HIDDEN_SINCE) else it[NOTIFICATION_HIDDEN_SINCE] = ms }
    }

    /** Whether the sync notification is hidden as NotificationWatch recorded it: its banner (a signal, no pause). */
    val notificationHiddenFlow: Flow<Boolean> = store.data.map { it[NOTIFICATION_HIDDEN_SINCE] != null }.distinctUntilChanged()

    /** Since when «Nearby devices» is missing; null while granted (NearbyWatch). */
    suspend fun nearbyOffSinceMs(): Long? = store.data.first()[NEARBY_OFF_SINCE]

    suspend fun setNearbyOffSinceMs(ms: Long?) {
        store.edit { if (ms == null) it.remove(NEARBY_OFF_SINCE) else it[NEARBY_OFF_SINCE] = ms }
    }

    /** What ForceStopCheck already knows of the system's exit records; null before the first check. */
    suspend fun forceStopMarks(): ForceStopMarks? {
        val seen = store.data.first()[FORCE_STOP_SEEN] ?: return null // absent until the first check
        return ForceStopMarks(decodeExits(seen))
    }

    suspend fun setForceStopMarks(marks: ForceStopMarks) {
        store.edit { it[FORCE_STOP_SEEN] = encodeExits(marks.seen) }
    }

    /** Wall time of the last completed direct session per peer («last seen», stage 4). */
    val peerSeenFlow: Flow<Map<DeviceId, Long>> = store.data.map { decodePeers(it[PEER_SEEN]) }.distinctUntilChanged()

    suspend fun peerSeen(): Map<DeviceId, Long> = decodePeers(store.data.first()[PEER_SEEN])

    /** Keeps the later of the stored and the new time. */
    suspend fun setPeerSeen(peer: DeviceId, atMs: Long) {
        store.edit {
            val all = decodePeers(it[PEER_SEEN]).toMutableMap()
            all[peer] = maxOf(all[peer] ?: Long.MIN_VALUE, atMs)
            it[PEER_SEEN] = encodePeers(all)
        }
    }

    /**
     * A wiped group or identity: everything here belonged to the old group, except the force-stop marks. Those
     * describe this device's exit history, and a wiped seen set would swallow the first force stop after the next
     * join.
     */
    suspend fun clear() {
        store.edit {
            val seen = it[FORCE_STOP_SEEN]
            it.clear()
            if (seen != null) it[FORCE_STOP_SEEN] = seen
        }
    }

    companion object {
        const val NAME = "kinsight_transport_state"
        private val LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val BT_OFF_SINCE = longPreferencesKey("bluetooth_off_since_ms")
        private val HEARTBEAT_AT = longPreferencesKey("heartbeat_at_ms")
        private val HEARTBEAT_VERSION = stringPreferencesKey("heartbeat_version")
        private val STOPPED_BY_USER = booleanPreferencesKey("stopped_by_user")
        private val STOPPED_AT = longPreferencesKey("stopped_at_ms")
        private val NOTIFICATION_HIDDEN_SINCE = longPreferencesKey("notification_hidden_since_ms")
        private val PEER_SEEN = stringPreferencesKey("peer_seen")
        private val NEARBY_OFF_SINCE = longPreferencesKey("nearby_off_since_ms")
        private val FORCE_STOP_SEEN = stringPreferencesKey("force_stop_seen")

        /** "hex=ms;hex=ms"; malformed parts are skipped (the file is a cache). */
        internal fun decodePeers(text: String?): Map<DeviceId, Long> = text.orEmpty().split(';').mapNotNull { part ->
            val (id, ms) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val time = ms.toLongOrNull() ?: return@mapNotNull null
            runCatching { DeviceId(id) to time }.getOrNull()
        }.toMap()

        /** "ms:pid;ms:pid"; malformed parts are skipped. */
        internal fun decodeExits(text: String?): Set<AppExit> = text.orEmpty().split(';').mapNotNull { part ->
            val (ms, pid) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            AppExit(ms.toLongOrNull() ?: return@mapNotNull null, pid.toIntOrNull() ?: return@mapNotNull null)
        }.toSet()

        internal fun encodeExits(all: Set<AppExit>): String = all.joinToString(";") { "${it.timestampMs}:${it.pid}" }

        internal fun encodePeers(all: Map<DeviceId, Long>): String = all.entries.joinToString(";") { "${it.key.hex}=${it.value}" }

        /**
         * [file] must end with ".preferences_pb"; one active instance per file in a process. A corrupt file starts
         * over empty: everything here is a cache or a hint (last sync, heartbeat mark, a pending Bluetooth-off start,
         * the stop flag), and losing it beats a service that throws on every read.
         */
        fun create(file: File, scope: CoroutineScope): TransportStateStore = TransportStateStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
