package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Preflight C5/C6 and fix round 1: leaving runs on the app scope, shows LEAVING, waits for a session, then forgets. */
class LeaveFlowTest {
    private val mom = TestDevice()

    @AfterTest
    fun cleanUp() = mom.close()

    private suspend fun TestDevice.leftCount() = store().stateEvents().count { it.body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP }

    /** LEFT_GROUP is committed and the leave job has taken its session baseline. */
    private suspend fun TestDevice.awaitBaseline() = withTimeout(5_000) {
        while (leftCount() == 0 || sessions.subscriptionCount.value == 0) delay(10)
        delay(100)
    }

    @Test
    fun leaveWaitsForASessionThenForgetsWithoutEverShowingRemoved() = runBlocking<Unit> {
        mom.sessions.value = SessionProgress(finished = 3, syncing = false)
        mom.group.createGroup("Mom")
        val seen = Collections.synchronizedList(mutableListOf<GroupState>())
        val watcher = mom.scope.launch { mom.group.state.collect { seen += it } }
        withTimeout(5_000) { mom.group.state.first { it is GroupState.Ready } }
        val job = mom.group.leaveAndForget()
        mom.awaitBaseline()
        assertEquals(GroupState.Leaving, mom.group.state.first { it !is GroupState.Loading })
        assertTrue(mom.groups.exists(), "the group stays until a session has carried LEFT_GROUP")
        mom.sessions.value = SessionProgress(finished = 4, syncing = false)
        withTimeout(5_000) { job.join() }
        assertFalse(mom.groups.exists())
        assertFalse(mom.group.leaving.value)
        assertNull(mom.group.leaveError.value)
        assertEquals(listOf("start:false", "start:true", "stop"), mom.sync.calls)
        withTimeout(5_000) { while (seen.lastOrNull() != GroupState.NoGroup) delay(10) }
        watcher.cancel()
        assertTrue(GroupState.Removed !in seen, "states seen: $seen")
        assertTrue(GroupState.Leaving in seen)
    }

    @Test
    fun aSessionInFlightAtCommitDoesNotCount() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.sessions.value = SessionProgress(finished = 3, syncing = true)
        val job = mom.group.leaveAndForget()
        mom.awaitBaseline()
        mom.sessions.value = SessionProgress(finished = 4, syncing = false) // the session that may have begun before LEFT_GROUP
        delay(200)
        assertTrue(mom.groups.exists())
        mom.sessions.value = SessionProgress(finished = 5, syncing = false)
        withTimeout(5_000) { job.join() }
        assertFalse(mom.groups.exists())
    }

    @Test
    fun aRestartedCounterStillCountsAsASession() = runBlocking<Unit> {
        mom.sessions.value = SessionProgress(finished = 5, syncing = false)
        mom.group.createGroup("Mom")
        val job = mom.group.leaveAndForget()
        mom.awaitBaseline()
        mom.sessions.value = SessionProgress(finished = 0, syncing = false) // the service restarted: a new engine counts from zero
        delay(200)
        assertTrue(mom.groups.exists())
        mom.sessions.value = SessionProgress(finished = 1, syncing = false)
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
        mom.awaitBaseline()
        assertEquals(1, mom.leftCount())
        mom.sessions.value = SessionProgress(finished = 1, syncing = false)
        withTimeout(5_000) { first.join() }
    }

    @Test
    fun theSoleAdminOfOthersCannotLeave() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kid = DeviceKeys.generate()
        mom.pipeline.appendOwn { a -> listOf(a.addMember(kid.sign.publicKey, kid.dh.publicKey, "Kid", DeviceRole.CHILD)) }
        withTimeout(5_000) { mom.group.leaveAndForget().join() }
        assertEquals(LeaveError.SOLE_ADMIN, mom.group.leaveError.value)
        assertEquals(0, mom.leftCount())
        assertTrue(mom.groups.exists())
        assertFalse(mom.group.leaving.value)
        mom.group.dismissLeaveError()
        assertNull(mom.group.leaveError.value)
    }

    @Test
    fun withoutAGroupNothingIsWiped() = runBlocking<Unit> {
        mom.prefs.setDeviceName("Pixel")
        mom.prefs.setOnboardingDone(true)
        withTimeout(5_000) { mom.group.leaveAndForget().join() }
        assertEquals(LeaveError.NO_GROUP, mom.group.leaveError.value)
        assertTrue(mom.prefs.current().onboardingDone)
        assertTrue("stop" !in mom.sync.calls)
    }

    @Test
    fun adminOpsWithoutAGroupFail() = runBlocking<Unit> {
        val other = DeviceKeys.generate().deviceId
        assertFailsWith<IllegalStateException> { mom.group.grantAdmin(other) }
        assertFailsWith<IllegalStateException> { mom.group.remove(other) }
        assertFailsWith<IllegalStateException> { mom.group.leave() }
        assertTrue(mom.sync.calls.isEmpty())
    }
}
