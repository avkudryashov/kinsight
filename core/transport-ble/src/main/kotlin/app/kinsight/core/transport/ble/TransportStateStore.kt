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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

data class HeartbeatMark(val atMs: Long, val version: String)

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

    companion object {
        const val NAME = "kinsight_transport_state"
        private val LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val BT_OFF_SINCE = longPreferencesKey("bluetooth_off_since_ms")
        private val HEARTBEAT_AT = longPreferencesKey("heartbeat_at_ms")
        private val HEARTBEAT_VERSION = stringPreferencesKey("heartbeat_version")
        private val STOPPED_BY_USER = booleanPreferencesKey("stopped_by_user")

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
