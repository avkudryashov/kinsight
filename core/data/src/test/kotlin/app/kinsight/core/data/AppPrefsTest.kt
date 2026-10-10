package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
}
