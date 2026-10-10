package app.kinsight.core.usage

import android.app.usage.UsageStatsManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidSourcesDeviceTest {
    private val context = grantUsageAccess()

    @Test
    fun usageAccessIsGrantedAfterAppops(): Unit {
        assertTrue(AppOpsUsageAccess(context).granted())
    }

    @Test
    fun theDefaultLauncherIsResolved(): Unit {
        val launchers = HomeLauncherSource(context.packageManager).launchers()
        assertEquals(1, launchers.size, "launchers=$launchers")
        assertFalse("android" in launchers)
    }

    @Test
    fun launcherIconIsAStable48pxWebp(): Unit {
        val pm = context.packageManager
        val pkg = HomeLauncherSource(pm).launchers().single()
        val first = assertNotNull(PackageAppInfoSource(pm).info(pkg))
        val second = assertNotNull(PackageAppInfoSource(pm).info(pkg))
        assertTrue(first.label.isNotBlank())
        assertContentEquals(first.iconWebp, second.iconWebp)
        assertEquals(first.hash(), second.hash())
        assertTrue(first.iconWebp.size < 16 * 1024, "icon is ${first.iconWebp.size} bytes")
        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(first.iconWebp, 0, first.iconWebp.size))
        assertEquals(IconEncoder.SIZE_PX to IconEncoder.SIZE_PX, bitmap.width to bitmap.height)
    }

    @Test
    fun invisiblePackagesHaveNoInfo(): Unit {
        assertEquals(null, PackageAppInfoSource(context.packageManager).info("com.example.not.installed"))
    }

    @Test
    fun clockAndReaderWork(): Unit {
        val sample = AndroidDeviceClock(context).sample()
        assertTrue(sample.bootCount > 0 && sample.elapsedMs > 0)
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = UsageStatsEventSource(usm).read(sample.wallMs - 7 * 86_400_000L, sample.wallMs)
        assertTrue(events.isNotEmpty(), "no usage events in the last 7 days")
        assertTrue(events.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }
}
