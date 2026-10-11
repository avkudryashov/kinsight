package app.kinsight.notifications

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.MainActivity
import android.Manifest
import android.app.NotificationManager
import app.kinsight.core.data.Alert
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlertNotifierTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val kid = DeviceId("cc".repeat(16))
    private val sis = DeviceId("dd".repeat(16))

    @Test
    fun alertsAlertOnceAndHideTheirTextOnTheLockScreen() {
        val n = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        val public = n.publicVersion
        assertEquals("Kinsight: an event on a child's phone", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun summariesHaveTheirOwnGenericPublicTitle() {
        val n = AlertNotifier.build(context, Alert.Summary(weekly = false, lines = listOf("Kid" to 60_000L)))
        assertEquals("Screen time summary", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    @Config(qualifiers = "ru")
    fun publicTitlesReadInRussian() {
        val i = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid))
        assertEquals("Kinsight: событие у ребёнка", i.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val s = AlertNotifier.build(context, Alert.Summary(weekly = true, lines = emptyList()))
        assertEquals("Сводка экранного времени", s.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun anIntegrityAlertOpensThatChildsDetail() {
        val n = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid))
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(kid.hex, MainActivity.deviceOf(intent))
    }

    @Test
    fun eachChildGetsItsOwnPendingIntent() {
        val a = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid)).contentIntent
        val b = AlertNotifier.build(context, Alert.Integrity("Sis", StateEvent.Kind.SHARING_PAUSED, 1_000, sis)).contentIntent
        assertNotEquals(shadowOf(a).requestCode, shadowOf(b).requestCode)
        assertTrue(shadowOf(a).flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertTrue(shadowOf(a).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(sis.hex, MainActivity.deviceOf(shadowOf(b).savedIntent))
    }

    @Test
    fun aChildThatLeftAndOtherAlertsOpenTheDashboard() {
        for (alert in listOf(
            Alert.Integrity("Kid", StateEvent.Kind.LEFT_GROUP, 1_000, kid),
            Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, null),
            Alert.NoContact("Kid", 1_000, kid),
            Alert.Summary(weekly = false, lines = emptyList()),
        )) {
            val intent = shadowOf(AlertNotifier.build(context, alert).contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertNull("$alert", MainActivity.deviceOf(intent))
        }
    }

    private fun threshold(child: DeviceId, target: ThresholdTarget, usedMs: Long) =
        Alert.Threshold(child, "Kid", target, null, usedMs, 3_600_000L)

    @Test
    fun aThresholdOpensThatChildsDetailAndHidesItsTextOnTheLockScreen() {
        val n = AlertNotifier.build(context, threshold(kid, ThresholdTarget.Total, 4_000_000L))
        assertEquals(AlertChannels.THRESHOLDS, n.channelId)
        assertEquals("Daily limit passed", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(kid.hex, MainActivity.deviceOf(shadowOf(n.contentIntent).savedIntent))
    }

    /**
     * A threshold re-posts only on a new marker (a new day, a changed limit): it must sound again even when yesterday's
     * notification of the same rule is still in the shade. The others keep alerting once per id.
     */
    @Test
    fun aThresholdAlertsAgainWhileTheOthersAlertOnce() {
        val t = AlertNotifier.build(context, threshold(kid, ThresholdTarget.Total, 4_000_000L))
        assertEquals(0, t.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        for (alert in listOf(
            Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid),
            Alert.NoContact("Kid", 1_000, kid),
            Alert.Summary(weekly = false, lines = emptyList()),
        )) {
            assertTrue("$alert", AlertNotifier.build(context, alert).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
    }

    @Test
    @Config(qualifiers = "ru")
    fun theThresholdPublicTitleReadsInRussian() {
        val n = AlertNotifier.build(context, threshold(kid, ThresholdTarget.Total, 4_000_000L))
        assertEquals("Лимит превышен", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun eachRuleHasOneStableNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        fun alerts() = nm.allNotifications.count { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        assertTrue(AlertNotifier.post(context, threshold(kid, ThresholdTarget.Total, 4_000_000L)))
        assertTrue(AlertNotifier.post(context, threshold(kid, ThresholdTarget.Total, 5_000_000L)))
        assertEquals(1, alerts())
        AlertNotifier.post(context, threshold(kid, ThresholdTarget.App("com.roblox.client"), 4_000_000L))
        AlertNotifier.post(context, threshold(sis, ThresholdTarget.Total, 4_000_000L))
        assertEquals(3, alerts())
    }

    @Test
    fun alertsAreStatusAndAChildsAlertsShareAGroup() {
        val paused = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid))
        val limit = AlertNotifier.build(context, threshold(kid, ThresholdTarget.Total, 4_000_000L))
        assertEquals(Notification.CATEGORY_STATUS, paused.category)
        assertEquals(Notification.CATEGORY_STATUS, limit.category)
        assertEquals(paused.group, limit.group)
        assertNotEquals(paused.group, AlertNotifier.build(context, Alert.Integrity("Sis", StateEvent.Kind.SHARING_PAUSED, 1_000, sis)).group)
        assertNull(AlertNotifier.build(context, Alert.Summary(weekly = false, lines = emptyList())).group)
    }

    @Test
    fun aGroupedAlertBringsASilentSummaryThatGoesWithTheLastAlert() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        AlertNotifier.post(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid))
        assertEquals(2, nm.allNotifications.size)
        val summary = nm.allNotifications.single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        AlertNotifier.post(context, Alert.Resolved(kid, StateEvent.Kind.SHARING_PAUSED, "Kid"))
        assertEquals(0, nm.allNotifications.size)
    }

    @Test
    fun aNewParentHasItsOwnPublicTitleAndAStableId() {
        val dad = DeviceId("bb".repeat(16))
        val n = AlertNotifier.build(context, Alert.NewParent("Dad", dad))
        assertEquals(AlertChannels.TRANSPARENCY, n.channelId)
        assertEquals("Kinsight: who sees your data", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /** The service applies a cancel asynchronously: the alert just cancelled may still be listed and must not count. */
    @Test
    fun aJustCancelledAlertDoesNotKeepItsGroupAlive() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val paused = Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid)
        AlertNotifier.post(context, paused)
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        val group = AlertNotifier.groupOf(paused)!!
        assertFalse(AlertNotifier.isEmptyGroup(active, group, emptySet()))
        assertTrue(AlertNotifier.isEmptyGroup(active, group, AlertNotifier.cancelledIds(Alert.Resolved(kid, StateEvent.Kind.SHARING_PAUSED, "Kid"))))
    }

    @Test
    fun aSwipedAwayGroupLosesItsSummaryOnTheNextSweep() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val nm = context.getSystemService(NotificationManager::class.java)
        val paused = Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid)
        val limit = threshold(sis, ThresholdTarget.Total, 4_000_000L)
        AlertNotifier.postAll(context, listOf(paused, limit))
        nm.cancel(AlertNotifier.idOf(paused)) // the user swiped it: the app is not told
        AlertNotifier.sweepSummaries(context)
        val left = shadowOf(nm).allNotifications
        assertEquals(2, left.size)
        assertTrue(left.all { it.group == AlertNotifier.groupOf(limit) })
    }

    @Test
    fun oneSummaryPerChildOnTheImportantChannel() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        AlertNotifier.postAll(context, listOf(
            Alert.Integrity("Kid", StateEvent.Kind.CLOCK_CHANGED, 1_000, kid),
            Alert.Integrity("Kid", StateEvent.Kind.DATA_GAP, 2_000, kid),
        ))
        val summaries = nm.allNotifications.filter { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(1, summaries.size)
        assertEquals(AlertChannels.IMPORTANT, summaries.single().channelId)
    }

    @Test
    fun aResolveAlsoClearsA07xNameKeyedGroup() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertChannels.ensure(context, Role.PARENT)
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        AlertNotifier.post(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, null))
        assertEquals(2, nm.allNotifications.size)
        AlertNotifier.post(context, Alert.Resolved(kid, StateEvent.Kind.SHARING_PAUSED, "Kid"))
        assertEquals(0, nm.allNotifications.size)
    }
}
