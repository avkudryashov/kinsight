package app.kinsight.core.data

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun resetIdentityForgetsTheKeysBeforeTheData() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.group.createGroup("Pixel")
        d.reset.resetIdentity()
        // Keys first: a crash after them leaves a new identity, never the old keys over an emptied log (audit A-4).
        assertEquals(listOf(false), d.identityResets)
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(Prefs(), d.prefs.current())
        assertFalse(d.groups.exists())
        assertFalse(d.resetMarker.exists())
    }

    @Test
    fun anInterruptedResetIsFinishedAtTheNextStart() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.identityWipeFails = IllegalStateException("killed")
        assertFailsWith<IllegalStateException> { d.reset.resetIdentity() }
        assertTrue(d.resetMarker.exists())
        assertFalse(d.groups.exists())
        assertTrue(d.db.entryDao().all().isNotEmpty()) // the keys go first: the data is still there
        assertEquals(ResetStatus.Failed(ResetKind.NEW_IDENTITY, "killed"), d.reset.status.value)
        d.reset.clearFailure()
        assertIs<ResetStatus.Failed>(d.reset.status.value) // no dismissing while the marker is pending
        d.identityWipeFails = null
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(1, d.identityResets.size)
        assertFalse(d.reset.finishInterruptedReset()) // nothing pending any more
    }

    @Test
    fun aCrashAfterTheKeyWipeIsFinishedAtTheNextStart() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.prefs.setDeviceName("Pixel")
        d.afterKeyWipeFails = IllegalStateException("killed")
        assertFailsWith<IllegalStateException> { d.reset.resetIdentity() }
        assertEquals(listOf(false), d.identityResets) // keys gone, data still there
        assertTrue(d.resetMarker.exists())
        d.afterKeyWipeFails = null
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(2, d.identityResets.size)
        assertEquals(Prefs(), d.prefs.current())
    }

    @Test
    fun aFinishedResetWithTheMarkerLeftBehindIsRunAgainHarmlessly() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.reset.resetIdentity()
        d.resetMarker.set()
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertFalse(d.groups.exists())
        assertEquals(Prefs(), d.prefs.current())
        assertEquals(ResetStatus.Idle, d.reset.status.value)
    }
}
