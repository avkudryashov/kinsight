package app.kinsight.core.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
            consentAtMs = p[CONSENT_AT],
            consentVersion = p[CONSENT_VERSION]
                ?: if (p[CONSENT_AT] != null) UsageSettings.LEGACY_CONSENT_VERSION else UsageSettings.CONSENT_VERSION,
            ownTimeLocal = p[OWN_TIME_LOCAL] ?: DEFAULTS.ownTimeLocal,
        )
    }

    suspend fun current(): UsageSettings = settings.first()

    /** Only [UsagePipeline] flips sharing: it records the SHARING_* event under its lock first. */
    internal suspend fun setSharing(on: Boolean) {
        store.edit { it[SHARING] = on }
    }

    /** Only [UsagePipeline.setOwnTimeLocal] flips it: turning off clears the local queue under its lock. */
    internal suspend fun setOwnTimeLocal(on: Boolean) {
        store.edit { it[OWN_TIME_LOCAL] = on }
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

    /** «Принимаю» on the consent screen of [version] (the current text unless a test says otherwise). */
    suspend fun recordConsent(atMs: Long, version: Int = UsageSettings.CONSENT_VERSION) {
        store.edit {
            it[CONSENT_AT] = atMs
            it[CONSENT_VERSION] = version
        }
    }

    /**
     * A child that agreed to an older text agreed to the current one: the version moves, the consent time stays (the
     * integrity trackers count their intervals from it). Without a consent nothing is recorded.
     */
    suspend fun upgradeConsent(version: Int = UsageSettings.CONSENT_VERSION) {
        store.edit { if (it[CONSENT_AT] != null) it[CONSENT_VERSION] = version }
    }

    /** A wiped group or a new identity: sharing off, consent forgotten, default retention. No event is signed. */
    suspend fun reset() {
        store.edit { it.clear() }
    }

    companion object {
        const val MAX_RETENTION_DAYS = 3650
        private val DEFAULTS = UsageSettings()
        private val SHARING = booleanPreferencesKey("share_usage")
        private val DETAIL_DAYS = intPreferencesKey("detail_retention_days")
        private val SUMMARY_DAYS = intPreferencesKey("summary_retention_days")
        private val CONSENT_AT = longPreferencesKey("consent_at_ms")
        private val CONSENT_VERSION = intPreferencesKey("consent_version")
        private val OWN_TIME_LOCAL = booleanPreferencesKey("own_time_local")

        /** [file] must end with ".preferences_pb"; one active instance per file in a process. */
        fun create(file: File, scope: CoroutineScope): SettingsStore =
            SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }
}
