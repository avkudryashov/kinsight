package app.kinsight.core.location

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

/** The tracker state between runs (the app's private files; `allowBackup` is off). */
interface TrackerStore {
    suspend fun load(): TrackerState

    suspend fun save(s: TrackerState)

    suspend fun clear()
}

class DataStoreTrackerStore(private val store: DataStore<Preferences>) : TrackerStore {
    override suspend fun load(): TrackerState = store.data.first().let { p ->
        TrackerState(
            mode = p[MODE]?.let { m -> Mode.entries.firstOrNull { it.name == m } } ?: Mode.UNKNOWN,
            inside = p[INSIDE].orEmpty(),
            registered = p[REGISTERED].orEmpty(),
            nextTickMs = p[NEXT_TICK],
        )
    }

    override suspend fun save(s: TrackerState) {
        store.edit { p ->
            p[MODE] = s.mode.name
            p[INSIDE] = s.inside
            p[REGISTERED] = s.registered
            val next = s.nextTickMs
            if (next != null) p[NEXT_TICK] = next else p.remove(NEXT_TICK)
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        const val NAME = "kinsight_location"

        /**
         * A file that no longer reads (Y4 parked minor) starts over from the empty state, logged: otherwise every entry
         * point, off() and forget() included, would throw and tracking could never be turned off.
         */
        fun create(file: File, scope: CoroutineScope, log: (String) -> Unit = { Log.w("KinsightLocation", it) }) = DataStoreTrackerStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { e ->
                    log("tracker state unreadable, starting over: ${e.javaClass.simpleName}")
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { file },
            ),
        )

        private val MODE = stringPreferencesKey("mode")
        private val INSIDE = stringSetPreferencesKey("inside")
        private val REGISTERED = stringSetPreferencesKey("registered")
        private val NEXT_TICK = longPreferencesKey("next_tick_ms")
    }
}
