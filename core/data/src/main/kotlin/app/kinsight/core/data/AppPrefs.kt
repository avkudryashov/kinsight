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
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** The parent's own category choices per package (spec §9.3, stage 5a): local, never in the log, not synced. */
    val categoryOverrides: Flow<Map<String, AppCategory>> = store.data.map { decodeOverrides(it[CATEGORY_OVERRIDES]) }.distinctUntilChanged()

    /** [category] null clears the override. */
    suspend fun setCategory(packageName: String, category: AppCategory?) {
        require(packageName.isNotEmpty() && packageName.none { it == '=' || it == '\n' }) { "bad package name" }
        store.edit {
            val m = decodeOverrides(it[CATEGORY_OVERRIDES]).toMutableMap()
            if (category == null) m.remove(packageName) else m[packageName] = category
            if (m.isEmpty()) it.remove(CATEGORY_OVERRIDES) else it[CATEGORY_OVERRIDES] = encodeOverrides(m)
        }
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
        private val CATEGORY_OVERRIDES = stringPreferencesKey("category_overrides")

        /** One `package=CATEGORY` per line; a malformed line or an unknown category is skipped. */
        private fun decodeOverrides(raw: String?): Map<String, AppCategory> = raw.orEmpty().lineSequence().mapNotNull { line ->
            val pkg = line.substringBefore('=', "")
            val cat = AppCategory.entries.firstOrNull { it.name == line.substringAfter('=', "") }
            if (pkg.isEmpty() || cat == null) null else pkg to cat
        }.toMap()

        private fun encodeOverrides(m: Map<String, AppCategory>): String =
            m.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value.name}" }

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
