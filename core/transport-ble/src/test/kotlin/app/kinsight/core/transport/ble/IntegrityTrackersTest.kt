package app.kinsight.core.transport.ble

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
    private val recorded = mutableListOf<Triple<StateEvent.Kind, Long, Long>>()
    private val record: suspend (StateEvent.Kind, Long, Long) -> Unit = { k, f, t -> recorded += Triple(k, f, t) }
    private val stops = StopTracker(state, record, clock = { now })
    private val watch = NotificationWatch(state, record, clock = { now })

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
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
        val nearby = NearbyWatch(state, record, clock = { now })
        nearby.onPermission(true)
        nearby.onPermission(false)
        now += 30_000
        nearby.onPermission(false)
        nearby.onPermission(true)
        assertEquals(listOf(Triple(StateEvent.Kind.NEARBY_PERMISSION_OFF, 1_000_000L, 1_030_000L)), recorded)
        assertNull(state.nearbyOffSinceMs())
    }

    @Test
    fun forceStopsNewerThanTheLastCheckAreRecordedOnce() = runBlocking<Unit> {
        val check = ForceStopCheck(state, record, clock = { now })
        check.onStart(listOf(500_000L)) // first check: only the baseline, history before it is not reported
        assertTrue(recorded.isEmpty())
        now += 60_000
        check.onStart(listOf(500_000L, 1_020_000L))
        assertEquals(listOf(Triple(StateEvent.Kind.APP_FORCE_STOPPED, 1_020_000L, 1_060_000L)), recorded)
        now += 60_000
        check.onStart(listOf(500_000L, 1_020_000L))
        assertEquals(1, recorded.size)
    }

    @Test
    fun failedRecordKeepsTheIntervalForTheNextTry() = runBlocking<Unit> {
        stops.onUserStop()
        val failing = StopTracker(state, record = { _, _, _ -> error("db down") }, clock = { now })
        runCatching { failing.onUserStart() }
        assertTrue(state.stoppedByUser())
        stops.onUserStart()
        assertEquals(1, recorded.size)
    }
}
