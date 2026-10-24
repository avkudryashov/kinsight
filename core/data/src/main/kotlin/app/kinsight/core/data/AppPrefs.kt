package app.kinsight.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.model.DeviceId
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

/**
 * Wrong PIN tries on a child's phone (spec §10) and until when it waits; local, goes with the group. [elapsedAtMs] and
 * [bootCount]: the elapsed clock and boot when the wait began (ChildLock: within that boot it runs on the elapsed clock).
 */
data class PinLock(val failures: Int = 0, val waitUntilMs: Long? = null, val elapsedAtMs: Long? = null, val bootCount: Int? = null)

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

    /**
     * The parent's local category choices of stage 5a (spec §9.3). Since stage 5c only the migration source
     * (UsageRepository.migrateLocalCategories): the app reads and writes the shared CategoryOverride entries instead.
     */
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

    /** After the migration has published them (stage 5c). */
    suspend fun clearCategoryOverrides() {
        store.edit { it.remove(CATEGORY_OVERRIDES) }
    }

    /**
     * The parent's local daily limits of stage 5b: only the migration source since the shared AppRule entries
     * (UsageRepository.migrateLocalThresholds), which read them, publish what nobody set and then [clearThresholds].
     * Ordered by id; the app no longer writes here.
     */
    val thresholds: Flow<List<ThresholdRule>> = store.data.map { decodeThresholds(it[THRESHOLDS]).values.toList() }.distinctUntilChanged()

    /** After the migration has published them. */
    suspend fun clearThresholds() {
        store.edit { it.remove(THRESHOLDS) }
    }

    suspend fun alertMarkers(): AlertMarkers = AlertMarkers.decode(store.data.first()[MARKERS])

    suspend fun setAlertMarkers(m: AlertMarkers) {
        store.edit { it[MARKERS] = m.encode() }
    }

    /** Parents the child acknowledged on Overview (U7); null until the first baseline. Goes with the group. */
    val acknowledgedParents: Flow<Set<DeviceId>?> = store.data.map { p -> p[ACK_PARENTS]?.let(::decodeIds) }.distinctUntilChanged()

    suspend fun setAcknowledgedParents(ids: Set<DeviceId>) {
        store.edit { it[ACK_PARENTS] = encodeIds(ids) }
    }

    /** «Понятно»: adds the parents the banner showed, in one edit (the alerts pass may write the baseline meanwhile). */
    suspend fun addAcknowledgedParents(ids: Set<DeviceId>) {
        store.edit { it[ACK_PARENTS] = encodeIds(it[ACK_PARENTS]?.let(::decodeIds).orEmpty() + ids) }
    }

    val pinLock: Flow<PinLock> = store.data.map {
        PinLock(it[PIN_FAILURES] ?: 0, it[PIN_WAIT_UNTIL], it[PIN_WAIT_ELAPSED], it[PIN_WAIT_BOOT])
    }.distinctUntilChanged()

    suspend fun pinLock(): PinLock = pinLock.first()

    suspend fun setPinLock(l: PinLock) {
        store.edit {
            if (l.failures == 0) it.remove(PIN_FAILURES) else it[PIN_FAILURES] = l.failures
            val until = l.waitUntilMs
            if (until == null) it.remove(PIN_WAIT_UNTIL) else it[PIN_WAIT_UNTIL] = until
            val elapsed = l.elapsedAtMs
            val boot = l.bootCount
            if (elapsed == null || boot == null) {
                it.remove(PIN_WAIT_ELAPSED)
                it.remove(PIN_WAIT_BOOT)
            } else {
                it[PIN_WAIT_ELAPSED] = elapsed
                it[PIN_WAIT_BOOT] = boot
            }
        }
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
        private val THRESHOLDS = stringPreferencesKey("threshold_rules")
        private val ACK_PARENTS = stringPreferencesKey("acknowledged_parents")
        private val PIN_FAILURES = intPreferencesKey("pin_failures")
        private val PIN_WAIT_UNTIL = longPreferencesKey("pin_wait_until_ms")
        private val PIN_WAIT_ELAPSED = longPreferencesKey("pin_wait_elapsed_ms")
        private val PIN_WAIT_BOOT = intPreferencesKey("pin_wait_boot")

        private fun encodeIds(ids: Set<DeviceId>): String = ids.map { d -> d.hex }.sorted().joinToString(",")

        /** Comma-separated device hex ids; a malformed one is skipped, "" is the empty set. */
        private fun decodeIds(raw: String): Set<DeviceId> =
            raw.split(',').filter { h -> h.length == 32 && h.all { it in '0'..'9' || it in 'a'..'f' } }.map(::DeviceId).toSet()

        /**
         * One `<child hex>|<target key>|<limit ms>` per line (target keys as in [ThresholdRule.idOf]), keyed by id; a
         * malformed line or one [ThresholdRule.of] refuses is skipped, a later line of the same (child, target) wins.
         */
        private fun decodeThresholds(raw: String?): Map<String, ThresholdRule> = raw.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val child = parts[0].takeIf { h -> h.length == 32 && h.all { it in '0'..'9' || it in 'a'..'f' } }?.let(::DeviceId)
            val target = ThresholdRule.targetOf(parts[1])
            val limit = parts[2].toLongOrNull()
            if (child == null || target == null || limit == null) null else ThresholdRule.of(child, target, limit)
        }.associateBy { it.id }.toSortedMap()

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
