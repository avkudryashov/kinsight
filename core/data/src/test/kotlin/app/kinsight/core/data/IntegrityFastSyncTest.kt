package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrityFastSyncTest {
    private var now = 1_000_000L
    private var stopped = false
    private var starts = 0
    private val fast = IntegrityFastSync(clock = { now }, stoppedByUser = { stopped }, startFast = { starts++ })

    @Test
    fun integrityEntryStartsFastModeOnce() = runBlocking<Unit> {
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(1, starts)
    }

    @Test
    fun secondWithinTwoMinutesDoesNotStartAgain() = runBlocking<Unit> {
        fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED)
        now += 119_999
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_RESUMED))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(1, starts)
        now += 1 // two minutes after the first start
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_RESUMED))
        assertEquals(2, starts)
    }

    @Test
    fun nonIntegrityEntryDoesNotStart() = runBlocking<Unit> {
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.NOTIFICATION_SHOWN))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.KIND_UNSPECIFIED))
        assertEquals(0, starts)
    }

    @Test
    fun everyIntegrityKindAndTheResumeStart() = runBlocking<Unit> {
        for (kind in AlertPlanner.INTEGRITY_KINDS + StateEvent.Kind.SHARING_RESUMED) {
            now += IntegrityFastSync.MIN_INTERVAL_MS
            assertTrue(fast.onOwnStateEvent(kind), kind.name)
        }
        assertEquals(AlertPlanner.INTEGRITY_KINDS.size + 1, starts)
    }

    @Test
    fun stoppedServiceIsNotStarted() = runBlocking<Unit> {
        stopped = true
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(0, starts)
        stopped = false // the user's Start: the refused event did not use up the two minutes
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(1, starts)
    }
}
