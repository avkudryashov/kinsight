package app.kinsight.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.Alert
import app.kinsight.core.data.AlertInput
import app.kinsight.core.data.AlertMarkers
import app.kinsight.core.data.AlertPlan
import app.kinsight.core.data.AlertPlanner
import app.kinsight.core.data.EventView
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlertsRunTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val kidSelf = kid.copy(isSelf = true)
    private val momOther = mom.copy(isSelf = false)

    private fun input(
        self: MemberView = mom,
        members: List<MemberView> = listOf(mom, kid),
        markers: AlertMarkers = AlertMarkers(initialized = true),
        events: List<EventView> = emptyList(),
        now: Long = 100_000_000L,
        joined: Map<DeviceId, Long> = emptyMap(),
    ) = AlertInput(self, members, emptyList(), events, emptyList(), now, ZoneId.of("UTC"), 24, SummaryMode.OFF, markers, joined)

    private fun paused(seq: Long) = EventView(kid.id, seq, StateEvent.Kind.SHARING_PAUSED, 0, 0, seq * 1_000, 0)
    private fun resumed(seq: Long) = EventView(kid.id, seq, StateEvent.Kind.SHARING_RESUMED, 0, 0, seq * 1_000, 0)

    /** One pass over [events] from [markers]: posts, stores, returns the stored markers. */
    private suspend fun pass(markers: AlertMarkers, events: List<EventView>, members: List<MemberView> = listOf(mom, kid)): AlertMarkers {
        val i = input(members = members, markers = markers, events = events)
        var stored = markers
        AlertsRun.deliver(context, i, AlertPlanner.plan(i)) { stored = it }
        return stored
    }

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun postsEveryAlertBeforeStoringTheMarkers() = runBlocking<Unit> {
        val i = input(events = listOf(paused(1), paused(2)))
        val plan = AlertPlanner.plan(i)
        assertEquals(2, plan.alerts.size)
        var postedWhenStored = -1
        var stored: AlertMarkers? = null
        AlertsRun.deliver(context, i, plan) {
            postedWhenStored = shadowOf(nm).allNotifications.size
            stored = it
        }
        // Both alerts share an id (same child, same kind): the newer one replaces the older on screen.
        assertEquals(1, postedWhenStored)
        assertEquals(plan.markers, stored)
    }

    @Test
    fun deniedPermissionPostsNothingAndKeepsTheMarkers() = runBlocking<Unit> {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val i = input(events = listOf(paused(1)))
        val plan = AlertPlanner.plan(i)
        var stored = false
        AlertsRun.deliver(context, i, plan) { stored = true }
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertFalse("markers must not move: the alert comes once notifications are allowed", stored)
        assertFalse(AlertNotifier.post(context, plan.alerts.single()))
    }

    @Test
    fun blockedNotificationsPostNothingAndKeepTheMarkers() = runBlocking<Unit> {
        shadowOf(nm).setNotificationsEnabled(false)
        val i = input(events = listOf(paused(1)))
        var stored = false
        AlertsRun.deliver(context, i, AlertPlanner.plan(i)) { stored = true }
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertFalse(stored)
    }

    @Test
    fun nothingToSayStoresTheMarkersEvenWithoutPermission() {
        val i = input(markers = AlertMarkers(), events = listOf(paused(4)))
        val plan = AlertPlanner.plan(i)
        assertTrue(plan.alerts.isEmpty())
        assertEquals(plan.markers, AlertsRun.markersToStore(i, plan, delivered = false))
    }

    @Test
    fun aParentsFirstSilentRunStillBaselinesHistoryButKeepsItsNews() {
        // First run, permission denied: history up to now is marked as seen, but the no-contact alert stays unsaid.
        val i = input(markers = AlertMarkers(), events = listOf(paused(4)), joined = mapOf(kid.id to 0L))
        val plan = AlertPlanner.plan(i)
        assertEquals(listOf(Alert.NoContact("Kid", 0L)), plan.alerts)
        val kept = AlertsRun.markersToStore(i, plan, delivered = false)!!
        assertTrue(kept.initialized)
        assertEquals(4L, kept.notifiedSeq[kid.id])
        assertTrue(kept.noContact.isEmpty())
        // Later, with the permission: the no-contact alert comes, the old pause does not.
        val next = AlertPlanner.plan(input(markers = kept, events = listOf(paused(4), paused(5)), joined = mapOf(kid.id to 0L)))
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 5_000, kid.id), Alert.NoContact("Kid", 0L)), next.alerts)
    }

    @Test
    fun aChildsUnsaidNewParentIsNotForgotten() {
        val i = input(self = kidSelf, members = listOf(momOther, kidSelf), markers = AlertMarkers(knownParents = emptySet(), initialized = true))
        val plan = AlertPlanner.plan(i)
        assertEquals(listOf(Alert.NewParent("Mom")), plan.alerts)
        assertNull(AlertsRun.markersToStore(i, plan, delivered = false))
        assertEquals(plan.markers, AlertsRun.markersToStore(i, plan, delivered = true))
    }

    @Test
    fun aJoinTimeFromTheFutureCountsAsNow() {
        val i = AlertsRun.clampJoined(input(now = 1_000L, joined = mapOf(kid.id to 5_000L, mom.id to 500L)))
        assertEquals(mapOf(kid.id to 1_000L, mom.id to 500L), i.joinedMs)
    }

    @Test
    fun runsNeverOverlap() = runBlocking<Unit> {
        val inside = AtomicInteger()
        var maxInside = 0
        (1..4).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                AlertsRun.serialized {
                    maxInside = maxOf(maxInside, inside.incrementAndGet())
                    delay(20)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInside)
    }

    @Test
    fun aResumeCancelsThePausedNotification() = runBlocking<Unit> {
        val afterPause = pass(AlertMarkers(initialized = true), listOf(paused(1)))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        val afterResume = pass(afterPause, listOf(paused(1), resumed(2)))
        assertTrue("the stale \"paused\" must go", shadowOf(nm).allNotifications.isEmpty())
        assertEquals(2L, afterResume.notifiedSeq[kid.id])
    }

    @Test
    fun pauseResumePauseLeavesExactlyOneCurrentNotification() = runBlocking<Unit> {
        var m = pass(AlertMarkers(initialized = true), listOf(paused(1)))
        m = pass(m, listOf(paused(1), resumed(2)))
        pass(m, listOf(paused(1), resumed(2), paused(3)))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        // All three in one pass: posted, cancelled, posted again.
        nm.cancelAll()
        pass(AlertMarkers(initialized = true), listOf(paused(1), resumed(2), paused(3)))
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun theResumeFindsThePauseByDeviceEvenAfterARename() = runBlocking<Unit> {
        val m = pass(AlertMarkers(initialized = true), listOf(paused(1)))
        pass(m, listOf(paused(1), resumed(2)), members = listOf(mom, kid.copy(name = "Kiddo")))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun aResumeLeavesOtherKindsAndChildrenAlone() = runBlocking<Unit> {
        val sis = DeviceId("dd".repeat(16))
        AlertNotifier.post(context, Alert.Integrity("Kid", StateEvent.Kind.LEFT_GROUP, 1_000, kid.id))
        AlertNotifier.post(context, Alert.Integrity("Sis", StateEvent.Kind.SHARING_PAUSED, 1_000, sis))
        AlertNotifier.post(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid.id))
        assertTrue(AlertNotifier.post(context, Alert.Resolved(kid.id, StateEvent.Kind.SHARING_PAUSED, "Kid")))
        val titles = shadowOf(nm).allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }.toSet()
        val left = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.LEFT_GROUP, 1_000, kid.id)).title
        val sisPaused = AlertText.of(context, Alert.Integrity("Sis", StateEvent.Kind.SHARING_PAUSED, 1_000, sis)).title
        val kidPaused = AlertText.of(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid.id)).title
        assertEquals(setOf(left, sisPaused), titles)
        assertFalse(kidPaused in titles)
    }

    @Test
    fun aNewPauseReplacesAnOldBuildsNameKeyedNotification() = runBlocking<Unit> {
        // Before integrity ids were keyed by device, a "paused" notification had the id built from the display name.
        val legacyId = ("i" + "Kid" + StateEvent.Kind.SHARING_PAUSED.name).hashCode()
        val old = AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 500, null))
        nm.notify(legacyId, old)
        assertTrue(AlertNotifier.post(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid.id)))
        assertNull(shadowOf(nm).getNotification(legacyId))
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun aResumeAlsoCancelsAnOldBuildsNameKeyedNotification() = runBlocking<Unit> {
        val legacyId = ("i" + "Kid" + StateEvent.Kind.SHARING_PAUSED.name).hashCode()
        nm.notify(legacyId, AlertNotifier.build(context, Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 500, null)))
        assertTrue(AlertNotifier.post(context, Alert.Resolved(kid.id, StateEvent.Kind.SHARING_PAUSED, "Kid")))
        assertNull(shadowOf(nm).getNotification(legacyId))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
}
