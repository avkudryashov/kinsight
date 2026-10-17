package app.kinsight

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
        val watch = ForegroundCheck.Watch { runs++ }
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
}
