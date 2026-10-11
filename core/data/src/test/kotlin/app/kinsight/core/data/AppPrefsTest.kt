package app.kinsight.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppPrefsTest {
    private val dir = Files.createTempDirectory("kinsight-prefs").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = AppPrefs.create(File(dir, "app.preferences_pb"), scope)

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun defaultsAndUpdates() = runBlocking<Unit> {
        assertEquals(Prefs(), prefs.current())
        prefs.setDeviceName("Pixel")
        prefs.setOnboardingDone(true)
        prefs.setSummaryMode(SummaryMode.WEEKLY)
        prefs.setNoContactHours(48)
        prefs.setLastPruneDate("2026-10-10")
        assertEquals(Prefs("Pixel", true, SummaryMode.WEEKLY, 48, "2026-10-10"), prefs.current())
        assertFailsWith<IllegalArgumentException> { prefs.setNoContactHours(0) }
        assertFailsWith<IllegalArgumentException> { prefs.setDeviceName(" ") }
    }

    @Test
    fun markersPersist() = runBlocking<Unit> {
        val m = AlertMarkers(notifiedSeq = mapOf(DeviceId("aa".repeat(16)) to 3L))
        prefs.setAlertMarkers(m)
        assertEquals(m, prefs.alertMarkers())
    }

    @Test
    fun resetForNewGroupKeepsOnlyTheName() = runBlocking<Unit> {
        prefs.setDeviceName("Pixel")
        prefs.setOnboardingDone(true)
        prefs.setAlertMarkers(AlertMarkers(lastSummaryDate = "2026-10-10"))
        prefs.resetForNewGroup()
        assertEquals(Prefs(deviceName = "Pixel"), prefs.current())
        assertEquals(AlertMarkers(), prefs.alertMarkers())
        prefs.reset()
        assertEquals(Prefs(), prefs.current())
    }

    @Test
    fun aCorruptFileStartsOver() = runBlocking<Unit> {
        val file = File(dir, "corrupt.preferences_pb")
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })
        val corrupt = AppPrefs.create(file, scope)
        assertEquals(Prefs(), corrupt.current())
        assertEquals(AlertMarkers(), corrupt.alertMarkers())
    }

    @Test
    fun outOfRangeNoContactHoursReadAsDefault() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "raw.preferences_pb") })
        val raw = AppPrefs(store)
        for (bad in listOf(0, -3, 169, Int.MAX_VALUE)) {
            store.edit { it[intPreferencesKey("no_contact_hours")] = bad }
            assertEquals(24, raw.current().noContactHours)
        }
        store.edit { it[intPreferencesKey("no_contact_hours")] = 168 }
        assertEquals(168, raw.current().noContactHours)
    }

    @Test
    fun categoryOverridesRoundTrip() = runBlocking<Unit> {
        assertEquals(emptyMap(), prefs.categoryOverrides.first())
        prefs.setCategory("com.roblox.client", AppCategory.PRODUCTIVITY)
        prefs.setCategory("com.example.news", AppCategory.NEWS)
        assertEquals(
            mapOf("com.roblox.client" to AppCategory.PRODUCTIVITY, "com.example.news" to AppCategory.NEWS),
            prefs.categoryOverrides.first(),
        )
        prefs.setCategory("com.roblox.client", AppCategory.GAMES)
        prefs.setCategory("com.example.news", null)
        prefs.setCategory("com.never.set", null)
        assertEquals(mapOf("com.roblox.client" to AppCategory.GAMES), prefs.categoryOverrides.first())
        // Survives reopening the file (one DataStore per file at a time: close the first).
        scope.coroutineContext.job.cancelAndJoin()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val again = AppPrefs.create(File(dir, "app.preferences_pb"), scope2)
            assertEquals(mapOf("com.roblox.client" to AppCategory.GAMES), again.categoryOverrides.first())
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun aMalformedOverrideIsSkipped() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "raw2.preferences_pb") })
        store.edit { it[stringPreferencesKey("category_overrides")] = "a.b=GAMES\nbroken\nc.d=NOPE\n=SOCIAL\ne.f=VIDEO" }
        assertEquals(mapOf("a.b" to AppCategory.GAMES, "e.f" to AppCategory.VIDEO), AppPrefs(store).categoryOverrides.first())
    }

    private val kidA = DeviceId("aa".repeat(16))
    private val kidB = DeviceId("bb".repeat(16))
    private val hour = 3_600_000L

    @Test
    fun thresholdsRoundTripOnePerChildAndTarget() = runBlocking<Unit> {
        assertEquals(emptyList(), prefs.thresholds.first())
        val total = ThresholdRule.of(kidA, ThresholdTarget.Total, 3 * hour)!!
        val games = ThresholdRule.of(kidA, ThresholdTarget.Category(AppCategory.GAMES), hour)!!
        val app = ThresholdRule.of(kidB, ThresholdTarget.App("com.example.game"), hour / 2)!!
        prefs.setThreshold(total)
        prefs.setThreshold(games)
        prefs.setThreshold(app)
        assertEquals(setOf(total, games, app), prefs.thresholds.first().toSet())
        // Same (child, target): replaced, not added.
        val raised = ThresholdRule.of(kidA, ThresholdTarget.Total, 4 * hour)!!
        prefs.setThreshold(raised)
        assertEquals(setOf(raised, games, app), prefs.thresholds.first().toSet())
        prefs.removeThreshold(games.id)
        prefs.removeThreshold("no-such-id")
        assertEquals(setOf(raised, app), prefs.thresholds.first().toSet())
        // Survives reopening the file.
        scope.coroutineContext.job.cancelAndJoin()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val again = AppPrefs.create(File(dir, "app.preferences_pb"), scope2)
            assertEquals(setOf(raised, app), again.thresholds.first().toSet())
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun anInvalidThresholdIsRefused() = runBlocking<Unit> {
        // Built around ThresholdRule.of: an off-step limit or an id not derived from child and target.
        assertFailsWith<IllegalArgumentException> { prefs.setThreshold(ThresholdRule("x", kidA, ThresholdTarget.Total, hour)) }
        assertFailsWith<IllegalArgumentException> {
            prefs.setThreshold(ThresholdRule(ThresholdRule.idOf(kidA, ThresholdTarget.Total), kidA, ThresholdTarget.Total, hour + 1))
        }
        assertEquals(emptyList(), prefs.thresholds.first())
    }

    @Test
    fun thresholdsAreWipedByBothResets() = runBlocking<Unit> {
        prefs.setThreshold(ThresholdRule.of(kidA, ThresholdTarget.Total, hour)!!)
        prefs.resetForNewGroup()
        assertEquals(emptyList(), prefs.thresholds.first())
        prefs.setThreshold(ThresholdRule.of(kidA, ThresholdTarget.Total, hour)!!)
        prefs.reset()
        assertEquals(emptyList(), prefs.thresholds.first())
    }

    @Test
    fun aMalformedThresholdLineIsSkipped() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "raw3.preferences_pb") })
        val a = "aa".repeat(16)
        store.edit {
            it[stringPreferencesKey("threshold_rules")] = listOf(
                "$a|total|3600000",
                "broken",
                "$a|cat:NOPE|3600000", // unknown category
                "$a|cat:GAMES|3600001", // off-step
                "$a|cat:GAMES|99999999999999999999", // not a long
                "short|total|3600000", // not a device id
                "$a|app:~other|3600000", // the remainder is not an app
                "$a|weird:x|3600000",
                "$a|app:com.x|900000",
                "",
            ).joinToString("\n")
        }
        assertEquals(
            setOf(ThresholdRule.of(kidA, ThresholdTarget.Total, hour)!!, ThresholdRule.of(kidA, ThresholdTarget.App("com.x"), hour / 4)!!),
            AppPrefs(store).thresholds.first().toSet(),
        )
    }

    @Test
    fun acknowledgedParentsPersistAndGoWithTheGroup() = runBlocking<Unit> {
        assertEquals(null, prefs.acknowledgedParents.first())
        val ids = setOf(DeviceId("aa".repeat(16)), DeviceId("bb".repeat(16)))
        prefs.setAcknowledgedParents(ids)
        assertEquals(ids, prefs.acknowledgedParents.first())
        prefs.setAcknowledgedParents(emptySet())
        assertEquals(emptySet(), prefs.acknowledgedParents.first(), "empty is a baseline, not «never stored»")
        prefs.resetForNewGroup()
        assertEquals(null, prefs.acknowledgedParents.first())
    }
}
