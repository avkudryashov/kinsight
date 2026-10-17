package app.kinsight.core.location

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The debug switches the emulator uses for task-6 steps 2 (a refused service) and 4 (a kill before armTick). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocationSeamsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        ServiceGate.reset()
        LocationDebug.refuse(start = false, foreground = false)
        LocationDebug.slowTick(0)
    }

    @After
    fun cleanUp() = setUp()

    @Test
    fun theSwitchesStartOffAndSayWhatTheyAre() {
        assertEquals("refuseStart=false refuseForeground=false tickDelayMs=0", LocationDebug.seams())
        assertEquals("refuseStart=true refuseForeground=false tickDelayMs=0", LocationDebug.refuse(start = true, foreground = false))
        assertEquals("refuseStart=true refuseForeground=false tickDelayMs=45000", LocationDebug.slowTick(45_000))
    }

    @Test
    fun aRefusedStartIsUncountedAndSendsNothing() {
        LocationDebug.refuse(start = true, foreground = false)
        assertFalse(AndroidCadence(app).startService())
        assertEquals(0, ServiceGate.pendingStarts)
        assertNull(shadowOf(app).nextStartedService)
        LocationDebug.refuse(start = false, foreground = false)
        assertTrue("the next tick starts it", AndroidCadence(app).startService())
    }

    @Test
    fun aRefusedForegroundStopsTheServiceByItsStartId() {
        LocationDebug.refuse(start = false, foreground = true)
        AndroidCadence(app).startService()
        val service = Robolectric.buildService(LocationService::class.java).create().startCommand(0, 3).get()
        assertEquals(3, shadowOf(service).stopSelfId)
        assertFalse(ServiceGate.isForeground)
        assertFalse("no notification was posted", shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test
    fun aHeldTickRunsAfterTheBroadcastIsReleased() = runTest {
        LocationDebug.slowTick(45_000)
        var ticked = false
        var released = false
        val work = launch { OutsideTick.run { ticked = true } }
        Broadcasts.finishWithin(this, work, Broadcasts.RECEIVER_BUDGET_MS) { released = true }
        runCurrent()
        advanceTimeBy(Broadcasts.RECEIVER_BUDGET_MS + 1)
        assertTrue("released at the budget", released)
        assertFalse("the tick (and its armTick) still waits", ticked)
        advanceTimeBy(45_000 - Broadcasts.RECEIVER_BUDGET_MS)
        assertTrue(ticked)
    }

    @Test
    fun withoutTheSwitchTheTickRunsAtOnce() = runTest {
        var ticked = false
        OutsideTick.run { ticked = true }
        assertTrue(ticked)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTickDelayPastTenMinutesIsRefused() {
        LocationDebug.slowTick(LocationDebug.MAX_TICK_DELAY_MS + 1)
    }
}
