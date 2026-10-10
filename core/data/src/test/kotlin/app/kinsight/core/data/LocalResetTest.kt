package app.kinsight.core.data

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalResetTest {
    private val d = TestDevice()

    @AfterTest
    fun cleanUp() = d.close()

    @Test
    fun forgetGroupLeavesNoTraceButTheName() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.prefs.setOnboardingDone(true)
        d.group.createGroup("Pixel")
        d.settings.recordConsent(1L)
        d.transport.setStoppedByUser(true)
        d.reset.forgetGroup()
        assertFalse(d.groups.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertNull(d.settings.current().consentAtMs)
        assertFalse(d.transport.stoppedByUser())
        assertEquals(Prefs(deviceName = "Pixel"), d.prefs.current())
        assertEquals("stop", d.sync.calls[1]) // after createGroup's start: the service stops before the wipe
        assertTrue(d.identityResets.isEmpty())
    }

    @Test
    fun resetIdentityWipesFirstThenForgetsTheKeys() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.group.createGroup("Pixel")
        d.reset.resetIdentity()
        assertEquals(listOf(true), d.identityResets) // the database was already empty when the keys went
        assertEquals(Prefs(), d.prefs.current())
        assertFalse(d.groups.exists())
    }
}
