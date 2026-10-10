package app.kinsight.notifications

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.model.proto.StateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlertTextTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun integrityAlertsNameTheChildAndUseTheIntegrityChannel() {
        for (kind in listOf(
            StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.USAGE_ACCESS_REVOKED, StateEvent.Kind.LEFT_GROUP,
            StateEvent.Kind.SYNC_STOPPED, StateEvent.Kind.NOTIFICATION_HIDDEN, StateEvent.Kind.NEARBY_PERMISSION_OFF,
            StateEvent.Kind.APP_FORCE_STOPPED,
        )) {
            val t = AlertText.of(context, Alert.Integrity("Kid", kind, 1_000))
            assertEquals(AlertChannels.INTEGRITY, t.channel)
            assertTrue(t.title, t.title.contains("Kid"))
        }
        assertTrue(AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000)).title.contains("paused"))
    }

    /** Every kind the planner reports has its own text: a kind added to the set without one fails here. */
    @Test
    fun everyPlannedIntegrityKindHasItsOwnEnglishText() {
        assertOwnTextForEveryKind()
    }

    @Test
    @Config(qualifiers = "ru")
    fun everyPlannedIntegrityKindHasItsOwnRussianText() {
        assertOwnTextForEveryKind()
        assertTrue(AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000)).title.contains("Bluetooth"))
    }

    private fun assertOwnTextForEveryKind() {
        val fallback = context.getString(R.string.alert_event, "Kid")
        val titles = AlertPlanner.INTEGRITY_KINDS.map { kind ->
            val t = AlertText.of(context, Alert.Integrity("Kid", kind, 1_000))
            assertEquals(AlertChannels.INTEGRITY, t.channel)
            assertTrue("$kind: ${t.title}", t.title.contains("Kid"))
            assertNotEquals("$kind falls back to the generic text", fallback, t.title)
            t.title
        }
        assertEquals("two kinds share a text: $titles", titles.size, titles.toSet().size)
    }

    @Test
    fun summaryListsEveryChildWithTime() {
        val t = AlertText.of(context, Alert.Summary(weekly = false, lines = listOf("Kid" to 7_200_000L, "Sis" to 1_800_000L)))
        assertEquals(AlertChannels.SUMMARIES, t.channel)
        assertTrue(t.body, t.body.contains("Kid: 2 h") && t.body.contains("Sis: 30 min"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianTexts() {
        assertTrue(AlertText.of(context, Alert.NewParent("Папа")).title.contains("Папа"))
        assertTrue(AlertText.of(context, Alert.NoContact("Kid", 1_000)).title.contains("на связи"))
    }

    @Test
    fun channelsAreCreated() {
        AlertChannels.ensure(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(AlertChannels.INTEGRITY))
        assertNotNull(nm.getNotificationChannel(AlertChannels.SUMMARIES))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(AlertChannels.INTEGRITY).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(AlertChannels.SUMMARIES).importance)
    }
}
