package app.kinsight

import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocationStateWatcherTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun send(action: String) {
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theSystemLocationSwitchRunsTheCheckOncePerBroadcast() {
        var runs = 0
        val watcher = LocationStateWatcher.Watch { runs++ }
        watcher.register(context)
        watcher.register(context) // the Application and a second caller must not double the callbacks
        send(LocationManager.PROVIDERS_CHANGED_ACTION)
        assertEquals(1, runs)
        send(LocationManager.MODE_CHANGED_ACTION)
        assertEquals(2, runs)
        send("app.kinsight.SOMETHING_ELSE")
        assertEquals(2, runs)
    }

    @Test
    fun anUnregisteredWatchHearsNothingAndLeavesNoReceiver() {
        var runs = 0
        val watcher = LocationStateWatcher.Watch { runs++ }
        val before = shadowOf(context).registeredReceivers.size
        watcher.register(context)
        assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
        watcher.unregister(context)
        watcher.unregister(context) // a second call is harmless
        assertEquals(before, shadowOf(context).registeredReceivers.size)
        send(LocationManager.PROVIDERS_CHANGED_ACTION)
        assertEquals(0, runs)
        watcher.register(context) // can watch again
        send(LocationManager.PROVIDERS_CHANGED_ACTION)
        assertEquals(1, runs)
        watcher.unregister(context)
    }
}
