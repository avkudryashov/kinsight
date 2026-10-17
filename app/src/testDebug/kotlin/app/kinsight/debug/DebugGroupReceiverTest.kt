package app.kinsight.debug

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.CloudDebugDisk
import app.kinsight.core.location.LocationDebug
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The adb wiring of the debug receiver: the address and guard the emulator scripts use, the action names and the extras'
 * defaults. Actions that need the whole app graph (a group, the runner) are exercised by the emulator scripts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DebugGroupReceiverTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun answer(action: String, extras: Intent.() -> Unit = {}): Pair<Int, String> =
        runBlocking { DebugGroupReceiver().answer(context, Intent("app.kinsight.debug.$action").apply(extras)) }

    @After
    fun cleanUp() {
        CloudDebugDisk.set(false)
        LocationDebug.refuse(start = false, foreground = false)
        LocationDebug.slowTick(0)
    }

    @Test
    fun adbAddressesTheReceiverByNameAndOnlyTheShellMaySend() {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, "app.kinsight.debug.DebugGroupReceiver"), 0)
        assertTrue(info.exported)
        assertEquals(Manifest.permission.DUMP, info.permission)
    }

    @Test
    fun theFakeDiskIsOnUnlessSaidOff() {
        assertEquals(0 to "fakeDisk=true", answer("CLOUD_FAKE_DISK"))
        assertNotNull(CloudDebugDisk.current)
        assertEquals(0 to "fakeDisk=false", answer("CLOUD_FAKE_DISK") { putExtra("on", false) })
        assertNull(CloudDebugDisk.current)
    }

    @Test
    fun theLocationSwitchesFollowTheirExtras() {
        assertEquals(0 to "refuseStart=true refuseForeground=false tickDelayMs=0", answer("LOCATION_REFUSE_FGS") { putExtra("at", "start") })
        assertEquals(0 to "refuseStart=false refuseForeground=true tickDelayMs=0", answer("LOCATION_REFUSE_FGS") { putExtra("at", "service") })
        assertEquals(0 to "refuseStart=false refuseForeground=false tickDelayMs=0", answer("LOCATION_REFUSE_FGS"))
        assertEquals(1, answer("LOCATION_REFUSE_FGS") { putExtra("at", "later") }.first)
        assertEquals(0 to "refuseStart=false refuseForeground=false tickDelayMs=45000", answer("LOCATION_SLOW_TICK") { putExtra("seconds", 45) })
        assertEquals(0 to "refuseStart=false refuseForeground=false tickDelayMs=0", answer("LOCATION_SLOW_TICK"))
        assertEquals(1, answer("LOCATION_SLOW_TICK") { putExtra("seconds", 601) }.first)
    }

    @Test
    fun theMapHooksAnswerFromTheAppsMapFolder() {
        java.io.File(context.filesDir, "maps").deleteRecursively()
        assertTrue(answer("MAP_STATUS").let { it.first == 0 && it.second.startsWith("present=none used=none catalog=none free=") })
        assertEquals(1 to "error: IllegalStateException: path extra missing", answer("MAP_INSTALL_LOCAL") { putExtra("x", 510); putExtra("y", 389) })
        assertEquals("x and y are required", 1, answer("MAP_INSTALL_LOCAL") { putExtra("path", "/nowhere") }.first)
    }

    @Test
    fun anUnknownActionIsAnErrorAnswerNotACrash() {
        assertEquals(1 to "error: IllegalStateException: unknown action app.kinsight.debug.NOPE", answer("NOPE"))
    }
}
