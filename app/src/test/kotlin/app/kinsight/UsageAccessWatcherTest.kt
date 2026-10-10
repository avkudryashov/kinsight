package app.kinsight

import android.app.AppOpsManager
import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UsageAccessWatcherTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aChangeOfUsageAccessRunsTheCollectionOnce() {
        var runs = 0
        val watcher = UsageAccessWatcher.Watch { runs++ }
        watcher.register(context)
        watcher.register(context) // a second call (Application and service) must not double the callbacks
        val ops = shadowOf(context.getSystemService(AppOpsManager::class.java))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertEquals(1, runs)
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertEquals(2, runs)
    }

    @Test
    fun otherPackagesAndOpsAreIgnored() {
        var runs = 0
        UsageAccessWatcher.Watch { runs++ }.register(context)
        val ops = shadowOf(context.getSystemService(AppOpsManager::class.java))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, 4242, "com.other", AppOpsManager.MODE_IGNORED)
        ops.setMode(AppOpsManager.OPSTR_CAMERA, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertEquals(0, runs)
    }
}
