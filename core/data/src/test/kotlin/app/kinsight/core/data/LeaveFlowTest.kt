package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Preflight C5/C6: leaving runs on the app scope, shows LEAVING, waits for a finished session, then forgets. */
class LeaveFlowTest {
    private val mom = TestDevice()

    @AfterTest
    fun cleanUp() = mom.close()

    private suspend fun TestDevice.awaitLeftSigned() = withTimeout(5_000) {
        while (store().stateEvents().none { it.body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP }) delay(10)
    }

    @Test
    fun leaveWaitsForAFinishedSessionThenForgets() = runBlocking<Unit> {
        mom.sessionsFinished.value = 3
        mom.group.createGroup("Mom")
        val job = mom.group.leaveAndForget()
        assertEquals(GroupState.Leaving, mom.group.state.first { it == GroupState.Leaving })
        mom.awaitLeftSigned()
        delay(100)
        assertTrue(mom.groups.exists(), "the group stays until a session has carried LEFT_GROUP")
        assertTrue(mom.group.leaving.value)
        mom.sessionsFinished.value = 4
        withTimeout(5_000) { job.join() }
        assertFalse(mom.groups.exists())
        assertFalse(mom.group.leaving.value)
        assertEquals(listOf("start:false", "start:true", "stop"), mom.sync.calls)
        assertEquals(GroupState.NoGroup, mom.group.state.first { it !is GroupState.Loading })
    }

    @Test
    fun aRestartedCounterStillCountsAsASession() = runBlocking<Unit> {
        mom.sessionsFinished.value = 5
        mom.group.createGroup("Mom")
        val job = mom.group.leaveAndForget()
        mom.awaitLeftSigned()
        mom.sessionsFinished.value = 0 // the service restarted: a new engine counts from zero
        delay(100)
        assertTrue(mom.groups.exists())
        mom.sessionsFinished.value = 1
        withTimeout(5_000) { job.join() }
        assertFalse(mom.groups.exists())
    }

    @Test
    fun noSessionForgetsAfterTheTimeout() = runBlocking<Unit> {
        val kid = TestDevice(leaveTimeoutMs = 300)
        try {
            kid.group.createGroup("Kid")
            withTimeout(5_000) { kid.group.leaveAndForget().join() }
            assertFalse(kid.groups.exists())
            assertTrue(kid.db.entryDao().all().isEmpty())
            assertFalse(kid.group.leaving.value)
        } finally {
            kid.close()
        }
    }

    @Test
    fun aSecondLeaveJoinsTheFirst() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val first = mom.group.leaveAndForget()
        assertSame(first, mom.group.leaveAndForget())
        mom.awaitLeftSigned()
        assertEquals(1, mom.store().stateEvents().count { it.body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP })
        mom.sessionsFinished.value = 1
        withTimeout(5_000) { first.join() }
    }
}
