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
}
