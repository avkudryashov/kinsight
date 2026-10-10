package app.kinsight.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.model.MembershipLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

enum class SummaryMode { OFF, DAILY, WEEKLY }

/** App-level local settings (spec §9.7, onboarding); never synchronised, excluded from backup like everything else. */
data class Prefs(
    val deviceName: String? = null,
    val onboardingDone: Boolean = false,
    val summaryMode: SummaryMode = SummaryMode.DAILY,
    val noContactHours: Int = 24,
    val lastPruneDate: String? = null,
)

class AppPrefs(private val store: DataStore<Preferences>) {
    val prefs: Flow<Prefs> = store.data.map { p ->
        Prefs(
            deviceName = p[DEVICE_NAME],
            onboardingDone = p[ONBOARDING_DONE] ?: false,
            summaryMode = p[SUMMARY_MODE]?.let { m -> SummaryMode.entries.firstOrNull { it.name == m } } ?: SummaryMode.DAILY,
            noContactHours = p[NO_CONTACT_HOURS]?.takeIf { it in NO_CONTACT_RANGE } ?: 24,
            lastPruneDate = p[LAST_PRUNE],
        )
    }

    suspend fun current(): Prefs = prefs.first()

    suspend fun setDeviceName(name: String) {
        require(name.isNotBlank() && MembershipLimits.nameOk(name)) { "device name must be 1..${MembershipLimits.MAX_NAME_CODE_POINTS} code points" }
        store.edit { it[DEVICE_NAME] = name.trim() }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun setSummaryMode(mode: SummaryMode) {
        store.edit { it[SUMMARY_MODE] = mode.name }
    }

    suspend fun setNoContactHours(hours: Int) {
        require(hours in NO_CONTACT_RANGE) { "no-contact hours must be 1..168" }
        store.edit { it[NO_CONTACT_HOURS] = hours }
    }

    suspend fun setLastPruneDate(date: String) {
        store.edit { it[LAST_PRUNE] = date }
    }

    suspend fun alertMarkers(): AlertMarkers = AlertMarkers.decode(store.data.first()[MARKERS])

    suspend fun setAlertMarkers(m: AlertMarkers) {
        store.edit { it[MARKERS] = m.encode() }
    }

    /** A wiped group: back to the first run; the device name the user typed stays. */
    suspend fun resetForNewGroup() {
        store.edit {
            val name = it[DEVICE_NAME]
            it.clear()
            if (name != null) it[DEVICE_NAME] = name
        }
    }

    /** A new identity: forget everything. */
    suspend fun reset() {
        store.edit { it.clear() }
    }

    companion object {
        const val NAME = "kinsight_app_prefs"
        private val NO_CONTACT_RANGE = 1..168
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val SUMMARY_MODE = stringPreferencesKey("summary_mode")
        private val NO_CONTACT_HOURS = intPreferencesKey("no_contact_hours")
        private val LAST_PRUNE = stringPreferencesKey("last_prune_date")
        private val MARKERS = stringPreferencesKey("alert_markers")

        /** [file] must end with ".preferences_pb"; a corrupt file starts over (only hints and markers live here). */
        fun create(file: File, scope: CoroutineScope): AppPrefs = AppPrefs(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
