package app.kinsight.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.R
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.Role
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlertTextTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun integrityAlertsNameTheChildAndUseTheImportantChannel() {
        for (kind in listOf(
            StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.USAGE_ACCESS_REVOKED, StateEvent.Kind.LEFT_GROUP,
            StateEvent.Kind.SYNC_STOPPED, StateEvent.Kind.NOTIFICATION_HIDDEN, StateEvent.Kind.NEARBY_PERMISSION_OFF,
            StateEvent.Kind.APP_FORCE_STOPPED,
        )) {
            val t = AlertText.of(context, Alert.Integrity("Kid", kind, 1_000))
            assertEquals(AlertChannels.IMPORTANT, t.channel)
            assertTrue(t.title, t.title.contains("Kid"))
        }
        assertTrue(AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000)).title.contains("paused"))
    }

    @Test
    fun shortBluetoothBreaksClockChangesAndGapsAreQuiet() {
        val min = 60_000L
        assertEquals(AlertChannels.EVENTS, AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000, durationMs = 10 * min)).channel)
        assertEquals(AlertChannels.IMPORTANT, AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000, durationMs = 45 * min)).channel)
        assertEquals(AlertChannels.EVENTS, AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.CLOCK_CHANGED, 1_000)).channel)
        assertEquals(AlertChannels.EVENTS, AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.DATA_GAP, 1_000)).channel)
        assertEquals(AlertChannels.IMPORTANT, AlertText.of(context, Alert.NoContact("Kid", 1_000)).channel)
        assertEquals(AlertChannels.TRANSPARENCY, AlertText.of(context, Alert.NewParent("Dad")).channel)
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
            assertEquals(AlertChannels.of(AlertPlanner.level(Alert.Integrity("Kid", kind, 1_000))), t.channel)
            assertTrue("$kind: ${t.title}", t.title.contains("Kid"))
            assertNotEquals("$kind falls back to the generic text", fallback, t.title)
            t.title
        }
        assertEquals("two kinds share a text: $titles", titles.size, titles.toSet().size)
        val advice = AlertPlanner.INTEGRITY_KINDS.map { kind -> AlertText.of(context, Alert.Integrity("Kid", kind, 1_000)).body.lines().last() }
        assertTrue("every body ends with advice: $advice", advice.none { it.isBlank() || it.startsWith("When") || it.startsWith("Когда") })
        assertEquals("two kinds share advice: $advice", advice.size, advice.toSet().size)
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
        assertTrue(AlertText.of(context, Alert.NoContact("Kid", 1_000)).title.contains("нет связи"))
    }

    @Test
    fun channelsFollowTheRole() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(AlertChannels.LEGACY_INTEGRITY, "Integrity", NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue("the 0.7.x channel was there", AlertChannels.ensure(context, Role.PARENT))
        assertNull("the 0.7.x channel goes", nm.getNotificationChannel(AlertChannels.LEGACY_INTEGRITY))
        assertFalse(AlertChannels.ensure(context, Role.PARENT))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(AlertChannels.IMPORTANT).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(AlertChannels.EVENTS).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(AlertChannels.SUMMARIES).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(AlertChannels.THRESHOLDS).importance)
        assertNull(nm.getNotificationChannel(AlertChannels.TRANSPARENCY))
        listOf(AlertChannels.IMPORTANT, AlertChannels.EVENTS, AlertChannels.SUMMARIES, AlertChannels.THRESHOLDS).forEach {
            assertTrue(it, nm.getNotificationChannel(it).description.orEmpty().isNotBlank())
        }
        AlertChannels.ensure(context, Role.CHILD) // the same phone reset and joined as a child
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(AlertChannels.TRANSPARENCY).importance)
        listOf(AlertChannels.IMPORTANT, AlertChannels.EVENTS, AlertChannels.SUMMARIES, AlertChannels.THRESHOLDS).forEach { assertNull(it, nm.getNotificationChannel(it)) }
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
        assertTrue(context.getString(R.string.channel_important_description).isNotBlank())
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
        assertEquals("Kid: сегодня 3 ч 10 мин (лимит 3 ч)", total.title)
        val games = AlertText.of(context, Alert.Threshold(kidId, "Kid", ThresholdTarget.Category(AppCategory.GAMES), null, 65 * min, 60 * min))
        assertEquals("Kid: Игры сегодня 1 ч 5 мин (лимит 1 ч)", games.title)
        AlertChannels.ensure(context, Role.PARENT)
        assertEquals("Правила", context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlertChannels.THRESHOLDS).name.toString())
    }

    @Test
    fun thresholdChannelIsCreatedAndDescribed() {
        AlertChannels.ensure(context, Role.PARENT)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlertChannels.THRESHOLDS)
        assertNotNull(channel)
        assertEquals("Rules", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue(channel.description.orEmpty().isNotBlank())
    }

    @Test
    fun zoneAlertsNameTheZone() {
        val exit = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.ZONE_EXIT, 1_000, zoneName = "Home"))
        assertEquals("Kid left “Home”", exit.title)
        assertEquals(AlertChannels.IMPORTANT, exit.channel)
        assertTrue(exit.body, exit.body.lines().last().startsWith("Open Kinsight"))
        val enter = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.ZONE_ENTER, 1_000))
        assertEquals("Kid arrived at a safe zone", enter.title)
        assertEquals(AlertChannels.EVENTS, enter.channel)
    }

    @Test
    @Config(qualifiers = "ru")
    fun zoneAlertsInRussianStayNeutral() {
        assertEquals("Kid: выход из зоны «Дом»", AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.ZONE_EXIT, 1_000, zoneName = "Дом")).title)
    }

    @Test
    fun noContactSaysForHowLong() {
        val now = 1_760_000_000_000L
        assertEquals("Kid: no contact for 7 h", AlertText.of(context, Alert.NoContact("Kid", now - 7 * 3_600_000L), nowMs = now).title)
    }

    /** Final review Important 2: an interval already over says how long it lasted, and asks for nothing to be fixed. */
    @Test
    fun aClosedIntervalSaysItsLengthAndNoFix() {
        val loc = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, durationMs = 3 * min))
        assertEquals("Kid: location access was off for 3 min", loc.title)
        assertEquals(1, loc.body.lines().size)
        assertTrue(loc.body, loc.body.startsWith("When: "))
        val off = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_DISABLED, 1_000, durationMs = 3 * min))
        assertEquals("Kid: location was off on the phone for 3 min", off.title)
        assertEquals(1, off.body.lines().size)
        val bt = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000, durationMs = 45 * min))
        assertEquals("Kid: Bluetooth was off for 45 min", bt.title)
        // Still open: the state and the advice as before.
        val open = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000))
        assertEquals("Kid: location access turned off", open.title)
        assertEquals(2, open.body.lines().size)
    }

    /** App blocking spec §5.4: blocking off is important and closes like location; a PIN unblock is quiet and names its app. */
    @Test
    fun blockingOffAndAPinUnblock() {
        val open = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLOCKING_OFF, 1_000))
        assertEquals(AlertChannels.IMPORTANT, open.channel)
        assertEquals("Kid: app blocking is off on the phone", open.title)
        assertEquals(2, open.body.lines().size)
        val closed = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLOCKING_OFF, 1_000, durationMs = 3 * min))
        assertEquals("Kid: app blocking was off on the phone for 3 min", closed.title)
        assertEquals(1, closed.body.lines().size)
        val pin = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLOCK_OVERRIDDEN, 1_000, packageName = "com.game"))
        assertEquals(AlertChannels.EVENTS, pin.channel)
        assertEquals("Kid: com.game unblocked by PIN until the end of the day", pin.title)
        val named = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLOCK_OVERRIDDEN, 1_000, packageName = "com.game", appLabel = "Space Game"))
        assertEquals("Kid: Space Game unblocked by PIN until the end of the day", named.title)
    }

    @Test
    @Config(qualifiers = "ru")
    fun aClosedIntervalSaysItsLengthInRussian() {
        assertEquals(
            "Kid: доступ к местоположению был выключен 3 мин",
            AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, durationMs = 3 * min)).title,
        )
        assertEquals(
            "Kid: местоположение на телефоне было выключено 3 мин",
            AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_DISABLED, 1_000, durationMs = 3 * min)).title,
        )
        assertEquals("Kid: Bluetooth был выключен 45 мин", AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000, durationMs = 45 * min)).title)
    }

    @Test
    @Config(qualifiers = "es")
    fun aClosedIntervalSaysItsLengthInSpanish() {
        assertEquals(
            "Kid: acceso a la ubicación desactivado durante 3 min",
            AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, durationMs = 3 * min)).title,
        )
        assertEquals(
            "Kid: Bluetooth desactivado durante 45 min",
            AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 1_000, durationMs = 45 * min)).title,
        )
    }
}
