package app.kinsight.core.transport.ble

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Final review Important 1: the notification a service start posts at once, before the group is read (and before the
 * Bluetooth-off, permission or storage paths can return early), must not offer Stop: the role is not known yet, and an
 * unknown role is a child's (spec §10 «нет выключателей»).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SyncServiceNotificationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theFirstNotificationOffersNoStopBeforeTheRoleIsKnown() {
        val controller = Robolectric.buildService(SyncService::class.java, Intent(app, SyncService::class.java))
        val service = controller.create().startCommand(0, 1).get()
        try {
            val posted = assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals("no Stop action", 0, posted.actions?.size ?: 0)
            assertEquals(app.getString(R.string.kinsight_child_title), posted.extras.getString("android.title"))
            assertEquals("a fresh install posts on the service channel until the role is known", ServiceChannels.CHILD, posted.channelId)
        } finally {
            controller.destroy()
        }
    }

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull("a foreground notification was posted", value)
        return value!!
    }
}
