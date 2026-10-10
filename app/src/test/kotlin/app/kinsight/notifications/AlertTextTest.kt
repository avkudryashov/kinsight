package app.kinsight.notifications

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
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

    @Test
    fun channelsAreDescribed() {
        AlertChannels.ensure(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(nm.getNotificationChannel(AlertChannels.INTEGRITY).description.orEmpty().isNotBlank())
        assertTrue(nm.getNotificationChannel(AlertChannels.SUMMARIES).description.orEmpty().isNotBlank())
    }

    @Test
    fun theTimeReadsAsWhen() {
        val body = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000)).body
        assertTrue(body, body.startsWith("When: "))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theTimeReadsAsWhenInRussian() {
        val body = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000)).body
        assertTrue(body, body.startsWith("Когда: "))
        assertTrue(context.getString(R.string.channel_integrity_description).isNotBlank())
    }

    private val kidId = DeviceId("cc".repeat(16))
    private val min = 60_000L

    @Test
    fun thresholdTextsNameTheChildTheTargetAndTheLimit() {
        val total = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.Total, null, 190 * min, 180 * min))
        assertEquals(AlertChannels.THRESHOLDS, total.channel)
        assertEquals("Kid: 3 h 10 min today (limit 3 h)", total.title)
        val games = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.Category(AppCategory.GAMES), null, 65 * min, 60 * min))
        assertEquals("Kid: Games 1 h 5 min today (limit 1 h)", games.title)
        val app = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.App("com.roblox.client"), "Roblox", 65 * min, 60 * min))
        assertEquals("Kid: Roblox 1 h 5 min today (limit 1 h)", app.title)
        val unknown = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.App("com.roblox.client"), null, 65 * min, 60 * min))
        assertEquals("Kid: com.roblox.client 1 h 5 min today (limit 1 h)", unknown.title)
        assertTrue(total.body.isNotBlank())
    }

    @Test
    @Config(qualifiers = "ru")
    fun thresholdTextsInRussian() {
        val total = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.Total, null, 190 * min, 180 * min))
        assertEquals("Kid: сегодня 3 ч 10 мин (порог 3 ч)", total.title)
        val games = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.Category(AppCategory.GAMES), null, 65 * min, 60 * min))
        assertEquals("Kid: Игры сегодня 1 ч 5 мин (порог 1 ч)", games.title)
        AlertChannels.ensure(context)
        assertEquals("Пороги", context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlertChannels.THRESHOLDS).name.toString())
    }

    @Test
    fun thresholdChannelIsCreatedAndDescribed() {
        AlertChannels.ensure(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlertChannels.THRESHOLDS)
        assertNotNull(channel)
        assertEquals("Thresholds", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue(channel.description.orEmpty().isNotBlank())
    }
}
