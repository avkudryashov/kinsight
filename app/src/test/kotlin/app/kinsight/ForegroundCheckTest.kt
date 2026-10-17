package app.kinsight

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ForegroundCheckTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Emulator finding: back from the system settings (no activity started before) the location check runs once. */
    @Test
    fun theCheckRunsEachTimeTheAppComesToTheForeground() {
        var runs = 0
        val watch = ForegroundCheck.Watch(CoroutineScope(Dispatchers.Unconfined)) { runs++ }
        app.registerActivityLifecycleCallbacks(watch)
        try {
            val first = Robolectric.buildActivity(Activity::class.java).create().start()
            assertEquals(1, runs)
            val second = Robolectric.buildActivity(Activity::class.java).create().start()
            assertEquals("another activity of an app already in front is not a return", 1, runs)
            second.stop()
            first.stop() // the system settings are in front now
            assertEquals(1, runs)
            first.restart()
            assertEquals(2, runs)
            val before = first.resume().get()
            first.configurationChange(android.content.res.Configuration().apply { orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE })
            assertNotSame("the activity was recreated", before, first.get())
            assertEquals("a rotation is no return to the foreground", 2, runs)
            first.pause().stop()
            first.restart()
            assertEquals("the recreated activity counts as started", 3, runs)
        } finally {
            app.unregisterActivityLifecycleCallbacks(watch)
        }
    }

    /** Regrant review Minor 3: the check never runs on the main thread, where the app graph would be built. */
    @Test
    fun theCheckRunsOffTheMainThread() {
        val onMain = CompletableDeferred<Boolean>()
        val watch = ForegroundCheck.Watch(CoroutineScope(Dispatchers.Default)) { onMain.complete(Looper.myLooper() == Looper.getMainLooper()) }
        app.registerActivityLifecycleCallbacks(watch)
        try {
            Robolectric.buildActivity(Activity::class.java).create().start()
            assertFalse(runBlocking { withTimeout(5_000) { onMain.await() } })
        } finally {
            app.unregisterActivityLifecycleCallbacks(watch)
        }
    }

    /**
     * Regrant review Minor 2: the first foreground of a process the launcher just started is skipped (its start-up ran the
     * same checks); a later return is not, and neither is the first foreground of a process started in the background.
     */
    @Test
    fun aColdStartsFirstForegroundIsSkippedOnly() {
        var runs = 0
        var cold = true
        val watch = ForegroundCheck.Watch(CoroutineScope(Dispatchers.Unconfined), coldStart = { cold }) { runs++ }
        app.registerActivityLifecycleCallbacks(watch)
        try {
            val a = Robolectric.buildActivity(Activity::class.java).create().start()
            assertEquals("the launcher start: start-up checks already", 0, runs)
            a.stop()
            a.restart()
            assertEquals("back from the system settings", 1, runs)
        } finally {
            app.unregisterActivityLifecycleCallbacks(watch)
        }
        var later = 0
        cold = false
        val background = ForegroundCheck.Watch(CoroutineScope(Dispatchers.Unconfined), coldStart = { cold }) { later++ }
        app.registerActivityLifecycleCallbacks(background)
        try {
            Robolectric.buildActivity(Activity::class.java).create().start()
            assertEquals("a process started in the background checks on its first foreground", 1, later)
        } finally {
            app.unregisterActivityLifecycleCallbacks(background)
        }
    }
}
