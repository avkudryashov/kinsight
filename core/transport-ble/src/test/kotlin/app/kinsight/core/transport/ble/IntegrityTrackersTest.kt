package app.kinsight.core.transport.ble

import android.app.ApplicationExitInfo
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrityTrackersTest {
    private val dir = Files.createTempDirectory("kinsight-integrity").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val state = TransportStateStore.create(File(dir, "t.preferences_pb"), scope)
    private var now = 1_000_000L

    /** When the own-usage gate opened (consent); null while it is closed and queueEvent drops everything. */
    private var openSince: Long? = 0L
    /** A transient failure to tell (the group file or the identity unreadable): the trackers must change nothing. */
    private var unknown = false
    private var accept = true
    private val recorded = mutableListOf<Triple<StateEvent.Kind, Long, Long>>()
    private val record: suspend (StateEvent.Kind, Long, Long) -> Boolean = { k, f, t ->
        val ok = accept && openSince != null
        if (ok) recorded += Triple(k, f, t)
        ok
    }
    private val gate: suspend () -> GateState = {
        if (unknown) GateState.Unknown else openSince?.let { GateState.Open(it) } ?: GateState.Closed
    }
    private val stops = StopTracker(state, record, gate, clock = { now })
    private val watch = NotificationWatch(state, record, gate, clock = { now })
    private val nearby = NearbyWatch(state, record, gate, clock = { now })
    private val check = ForceStopCheck(state, record, gate, clock = { now })

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun aHiddenNotificationDuringOnboardingIsNotHiding() = runBlocking<Unit> {
        // Emulator run 2026-10-10, finding 3: the service starts at the join, the notification permission is asked two
        // screens later; the parent got «the sync notification is hidden» for that onboarding grace.
        var onboarded = false
        val w = NotificationWatch(state, record, gate, clock = { now }, onboardingDone = { onboarded })
        w.onVisibility(false)
        now += 60_000
        w.onVisibility(true)
        assertEquals(emptyList(), recorded)
        assertNull(state.notificationHiddenSinceMs())

        // Finished, but the user refused notifications: that is hiding, openly reported.
        onboarded = true
        w.onVisibility(false)
        assertEquals(listOf(Triple(StateEvent.Kind.NOTIFICATION_HIDDEN, now, now)), recorded)
    }

    @Test
    fun stopThenStartRecordsOneInterval() = runBlocking<Unit> {
        stops.onUserStop()
        assertTrue(state.stoppedByUser())
        now += 5_000
        stops.onUserStop() // a second Stop keeps the first time
        now += 5_000
        stops.onUserStart()
        assertEquals(listOf(Triple(StateEvent.Kind.SYNC_STOPPED, 1_000_000L, 1_010_000L)), recorded)
        assertFalse(state.stoppedByUser())
        assertNull(state.stoppedAtMs())
    }

    @Test
    fun startWithoutStopRecordsNothing() = runBlocking<Unit> {
        stops.onUserStart()
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun hiddenThenShownRecordsBothOnce() = runBlocking<Unit> {
        watch.onVisibility(true)
        watch.onVisibility(false)
        now += 60_000
        watch.onVisibility(false)
        watch.onVisibility(true)
        watch.onVisibility(true)
        assertEquals(
            listOf(
                Triple(StateEvent.Kind.NOTIFICATION_HIDDEN, 1_000_000L, 1_000_000L),
                Triple(StateEvent.Kind.NOTIFICATION_SHOWN, 1_000_000L, 1_060_000L),
            ),
            recorded,
        )
    }

    @Test
    fun nearbyRevokedThenGrantedRecordsOneInterval() = runBlocking<Unit> {
        nearby.onPermission(true)
        nearby.onPermission(false)
        now += 30_000
        nearby.onPermission(false)
        nearby.onPermission(true)
        assertEquals(listOf(Triple(StateEvent.Kind.NEARBY_PERMISSION_OFF, 1_000_000L, 1_030_000L)), recorded)
        assertNull(state.nearbyOffSinceMs())
    }

    @Test
    fun forceStopsAreRecordedOnceByIdentity() = runBlocking<Unit> {
        check.onStart(listOf(AppExit(500_000L, 1))) // first check: only the baseline, history before it is not reported
        assertTrue(recorded.isEmpty())
        now += 60_000
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(1_020_000L, 2)))
        assertEquals(listOf(Triple(StateEvent.Kind.APP_FORCE_STOPPED, 1_020_000L, 1_060_000L)), recorded)
        now += 60_000
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(1_020_000L, 2)))
        assertEquals(1, recorded.size)
    }

    @Test
    fun failedRecordClearsTheFlagButKeepsTheInterval() = runBlocking<Unit> {
        stops.onUserStop()
        val failing = StopTracker(state, record = { _, _, _ -> error("db down") }, gate = gate, clock = { now })
        runCatching { failing.onUserStart() }
        assertFalse(state.stoppedByUser()) // the flag never sticks because of a failed event
        assertEquals(1_000_000L, state.stoppedAtMs())
        now += 1_000
        stops.onUserStart()
        assertEquals(listOf(Triple(StateEvent.Kind.SYNC_STOPPED, 1_000_000L, 1_001_000L)), recorded)
    }

    // Fix round 1, ruling 1: the first force stop after joining is not lost.

    @Test
    fun forceStopAfterJoiningIsRecorded() = runBlocking<Unit> {
        openSince = null // no group yet: the gate is closed, the check only sets the baseline
        check.onStart(emptyList())
        openSince = 1_000_000L // joined and consented
        now = 2_000_000L
        check.onStart(listOf(AppExit(1_500_000L, 42))) // force stopped after joining, next start
        assertEquals(listOf(Triple(StateEvent.Kind.APP_FORCE_STOPPED, 1_500_000L, 2_000_000L)), recorded)
    }

    @Test
    fun forgettingTheGroupKeepsTheForceStopBaseline() = runBlocking<Unit> {
        check.onStart(listOf(AppExit(500_000L, 1)))
        state.clear()
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(1_200_000L, 2)))
        assertEquals(listOf(Triple(StateEvent.Kind.APP_FORCE_STOPPED, 1_200_000L, 1_200_000L)), recorded) // to = max(stop, now)
    }

    // Fix round 1, ruling 2: identity, not wall time, decides what is new.

    @Test
    fun clockSetForwardThenBackHidesNoForceStop() = runBlocking<Unit> {
        check.onStart(listOf(AppExit(500_000L, 1)))
        now = 9_000_000L // clock set forward
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(8_900_000L, 2)))
        now = 700_000L // and back
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(8_900_000L, 2), AppExit(650_000L, 3)))
        check.onStart(listOf(AppExit(500_000L, 1), AppExit(8_900_000L, 2), AppExit(650_000L, 3)))
        assertEquals(listOf(8_900_000L, 650_000L), recorded.map { it.second })
        assertTrue(recorded.all { it.first == StateEvent.Kind.APP_FORCE_STOPPED })
    }

    @Test
    fun anUpdateOnApi33IsNotAForceStop() {
        val user = ApplicationExitInfo.REASON_USER_REQUESTED
        assertFalse(ForceStopCheck.isUserForceStop(user, 1_000_000L, sdkInt = 33, lastUpdateMs = 970_000L))
        assertFalse(ForceStopCheck.isUserForceStop(user, 1_000_000L, sdkInt = 31, lastUpdateMs = 1_005_000L))
        assertTrue(ForceStopCheck.isUserForceStop(user, 1_000_000L, sdkInt = 33, lastUpdateMs = 900_000L))
        assertTrue(ForceStopCheck.isUserForceStop(user, 1_000_000L, sdkInt = 34, lastUpdateMs = 990_000L))
        // Fix round 2: an update long after a force stop does not hide it.
        assertTrue(ForceStopCheck.isUserForceStop(user, 1_000_000L, sdkInt = 33, lastUpdateMs = 5_000_000L))
        assertFalse(ForceStopCheck.isUserForceStop(ApplicationExitInfo.REASON_CRASH, 1_000_000L, sdkInt = 34, lastUpdateMs = 0L))
    }

    // Fix round 1, ruling 3: a closed gate (before consent) keeps nothing; after consent the state is reported fresh.

    @Test
    fun stopBeforeConsentIsReportedFromConsent() = runBlocking<Unit> {
        openSince = null
        stops.onUserStop()
        assertTrue(state.stoppedByUser())
        assertNull(state.stoppedAtMs())
        openSince = 1_100_000L
        now = 1_200_000L
        stops.onUserStart()
        assertEquals(listOf(Triple(StateEvent.Kind.SYNC_STOPPED, 1_100_000L, 1_200_000L)), recorded)
        assertFalse(state.stoppedByUser())
    }

    @Test
    fun hiddenBeforeConsentIsReportedAfterIt() = runBlocking<Unit> {
        openSince = null
        watch.onVisibility(false)
        assertTrue(recorded.isEmpty())
        assertNull(state.notificationHiddenSinceMs())
        openSince = 1_000_000L
        now = 1_100_000L
        watch.onVisibility(false)
        now = 1_200_000L
        watch.onVisibility(true)
        assertEquals(
            listOf(
                Triple(StateEvent.Kind.NOTIFICATION_HIDDEN, 1_100_000L, 1_100_000L),
                Triple(StateEvent.Kind.NOTIFICATION_SHOWN, 1_100_000L, 1_200_000L),
            ),
            recorded,
        )
    }

    @Test
    fun nearbyOffBeforeConsentIsReportedFromTheFirstCheckAfterIt() = runBlocking<Unit> {
        openSince = null
        nearby.onPermission(false)
        assertNull(state.nearbyOffSinceMs())
        openSince = 1_000_000L
        now = 1_100_000L
        nearby.onPermission(false)
        now = 1_300_000L
        nearby.onPermission(true)
        assertEquals(listOf(Triple(StateEvent.Kind.NEARBY_PERMISSION_OFF, 1_100_000L, 1_300_000L)), recorded)
    }

    @Test
    fun forceStopBeforeConsentIsNeverReported() = runBlocking<Unit> {
        check.onStart(emptyList())
        openSince = null
        check.onStart(listOf(AppExit(1_100_000L, 5)))
        openSince = 1_200_000L
        check.onStart(listOf(AppExit(1_100_000L, 5)))
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun refusedEventsKeepTheStateForTheNextTry() = runBlocking<Unit> {
        accept = false // the gate closed between the tracker's check and queueEvent
        watch.onVisibility(false)
        assertNull(state.notificationHiddenSinceMs())
        check.onStart(emptyList())
        check.onStart(listOf(AppExit(1_050_000L, 7)))
        accept = true
        watch.onVisibility(false)
        check.onStart(listOf(AppExit(1_050_000L, 7)))
        assertEquals(
            listOf(StateEvent.Kind.NOTIFICATION_HIDDEN, StateEvent.Kind.APP_FORCE_STOPPED),
            recorded.map { it.first },
        )
    }

    // Fix round 2.

    @Test
    fun anExitOlderThanTheFirstCheckButUnseenIsReported() = runBlocking<Unit> {
        check.onStart(listOf(AppExit(900_000L, 1)))
        check.onStart(listOf(AppExit(900_000L, 1), AppExit(400_000L, 2))) // identity only: no timestamp baseline
        assertEquals(listOf(400_000L), recorded.map { it.second })
    }

    @Test
    fun anUnknownGateKeepsEveryOpenInterval() = runBlocking<Unit> {
        watch.onVisibility(false)
        nearby.onPermission(false)
        check.onStart(emptyList())
        unknown = true
        now += 10_000
        watch.onVisibility(true)
        nearby.onPermission(true)
        check.onStart(listOf(AppExit(1_005_000L, 3)))
        assertEquals(1_000_000L, state.notificationHiddenSinceMs())
        assertEquals(1_000_000L, state.nearbyOffSinceMs())
        unknown = false
        now += 10_000
        watch.onVisibility(true)
        nearby.onPermission(true)
        check.onStart(listOf(AppExit(1_005_000L, 3)))
        assertEquals(
            listOf(
                StateEvent.Kind.NOTIFICATION_HIDDEN,
                StateEvent.Kind.NOTIFICATION_SHOWN,
                StateEvent.Kind.NEARBY_PERMISSION_OFF,
                StateEvent.Kind.APP_FORCE_STOPPED,
            ),
            recorded.map { it.first },
        )
    }

    @Test
    fun anUnknownGateAtStopStillKeepsTheStopTime() = runBlocking<Unit> {
        unknown = true
        stops.onUserStop()
        unknown = false
        now += 5_000
        stops.onUserStart()
        assertEquals(listOf(Triple(StateEvent.Kind.SYNC_STOPPED, 1_000_000L, 1_005_000L)), recorded)
    }

    @Test
    fun aRefusedStopIntervalIsRetriedByThePeriodicCheck() = runBlocking<Unit> {
        stops.onUserStop()
        accept = false
        now += 5_000
        stops.onUserStart()
        assertFalse(state.stoppedByUser())
        accept = true
        now += 5_000
        stops.retryPending()
        stops.retryPending() // nothing left
        assertEquals(listOf(Triple(StateEvent.Kind.SYNC_STOPPED, 1_000_000L, 1_010_000L)), recorded)
        assertNull(state.stoppedAtMs())
    }
}
