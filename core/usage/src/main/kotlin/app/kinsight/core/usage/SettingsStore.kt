package app.kinsight.core.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/** «Делиться» and the retention periods (spec §7: Jetpack DataStore). Local only, never synchronised (§4.7). */
class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<UsageSettings> = store.data.map { p ->
        UsageSettings(
            sharing = p[SHARING] ?: DEFAULTS.sharing,
            detailRetentionDays = p[DETAIL_DAYS] ?: DEFAULTS.detailRetentionDays,
            summaryRetentionDays = p[SUMMARY_DAYS] ?: DEFAULTS.summaryRetentionDays,
        )
    }

    suspend fun current(): UsageSettings = settings.first()

    suspend fun setSharing(on: Boolean) {
        store.edit { it[SHARING] = on }
    }

    suspend fun setRetention(detailDays: Int, summaryDays: Int) {
        require(detailDays in 1..summaryDays && summaryDays <= MAX_RETENTION_DAYS) {
            "retention must satisfy 1 <= detail ($detailDays) <= summary ($summaryDays) <= $MAX_RETENTION_DAYS"
        }
        store.edit {
            it[DETAIL_DAYS] = detailDays
            it[SUMMARY_DAYS] = summaryDays
        }
    }

    companion object {
        const val MAX_RETENTION_DAYS = 3650
        private val DEFAULTS = UsageSettings()
        private val SHARING = booleanPreferencesKey("share_usage")
        private val DETAIL_DAYS = intPreferencesKey("detail_retention_days")
        private val SUMMARY_DAYS = intPreferencesKey("summary_retention_days")

        /** [file] must end with ".preferences_pb"; one active instance per file in a process. */
        fun create(file: File, scope: CoroutineScope): SettingsStore =
            SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }
}
