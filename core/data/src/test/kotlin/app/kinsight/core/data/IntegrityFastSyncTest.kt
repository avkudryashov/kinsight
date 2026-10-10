package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrityFastSyncTest {
    private var now = 1_000_000L
    private var stopped = false
    private var starts = 0
    private var startWorks = true
    private val fast = IntegrityFastSync(clock = { now }, stoppedByUser = { stopped }, startFast = { starts++; startWorks })

    @Test
    fun integrityEntryStartsFastModeOnce() = runBlocking<Unit> {
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(1, starts)
    }

    @Test
    fun secondWithinTwoMinutesDoesNotStartAgain() = runBlocking<Unit> {
        fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED)
        now += 119_999
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.USAGE_ACCESS_REVOKED))
        assertEquals(1, starts)
        now += 1 // two minutes after the first start
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(2, starts)
    }

    @Test
    fun aCloserOfTheLastFastStartBypassesTheIntervalOnce() = runBlocking<Unit> {
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        now += 90_000 // a resume 90 s after the pause must not leave the parent's "paused" notification standing
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_RESUMED))
        assertEquals(2, starts)
        now += 10_000 // a second resume within two minutes: the interval holds again
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_RESUMED))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(2, starts)
    }

    @Test
    fun aCloserOfAnotherOpenerKeepsTheInterval() = runBlocking<Unit> {
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        now += 30_000
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.NOTIFICATION_SHOWN))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.NOTIFICATION_HIDDEN))
        now += 30_000
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_RESUMED))
        assertEquals(2, starts)
    }

    @Test
    fun theStartGoesThroughTheSyncControlUnlessAWipeRuns() = runBlocking<Unit> {
        val sync = FakeSyncControl()
        val reset = MutableStateFlow<ResetStatus>(ResetStatus.Running(ResetKind.FORGET_GROUP))
        val start = IntegrityFastSync.startThrough(sync, reset)
        assertFalse(start())
        assertEquals(listOf("requestFast:refused"), sync.calls.toList())
        reset.value = ResetStatus.Idle
        assertTrue(start())
        assertEquals(listOf("requestFast:refused", "requestFast"), sync.calls.toList())
    }

    @Test
    fun nonIntegrityEntryDoesNotStart() = runBlocking<Unit> {
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.USAGE_ACCESS_RESTORED))
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.KIND_UNSPECIFIED))
        assertEquals(0, starts)
    }

    @Test
    fun everyIntegrityKindAndEveryCloserStart() = runBlocking<Unit> {
        assertEquals(AlertPlanner.INTEGRITY_KINDS + AlertPlanner.CLOSERS.values, IntegrityFastSync.KINDS)
        assertTrue(StateEvent.Kind.SHARING_RESUMED in IntegrityFastSync.KINDS)
        assertTrue(StateEvent.Kind.NOTIFICATION_SHOWN in IntegrityFastSync.KINDS)
        for (kind in IntegrityFastSync.KINDS) {
            now += IntegrityFastSync.MIN_INTERVAL_MS
            assertTrue(fast.onOwnStateEvent(kind), kind.name)
        }
        assertEquals(IntegrityFastSync.KINDS.size, starts)
    }

    @Test
    fun failedStartDoesNotUseUpTheInterval() = runBlocking<Unit> {
        startWorks = false // no group, or the system refused the service start
        assertFalse(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        startWorks = true
        now += 1
        assertTrue(fast.onOwnStateEvent(StateEvent.Kind.SHARING_PAUSED))
        assertEquals(2, starts)
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
