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
        assertEquals(listOf("start:false", "startByUser", "start:true", "stop"), mom.sync.calls)
        withTimeout(5_000) { while (seen.lastOrNull() != GroupState.NoGroup) delay(10) }
        watcher.cancel()
        assertTrue(GroupState.Removed !in seen, "states seen: $seen")
        assertTrue(GroupState.Leaving in seen)
    }

    /** The process dies after LEFT_GROUP is committed, before the wipe: the next start reads Removed, «start over» recovers. */
    @Test
    fun aCrashBetweenLeftGroupAndTheWipeEndsOnRemovedAndTheResetRecovers() = runBlocking<Unit> {
        mom.prefs.setDeviceName("Mom")
        mom.group.createGroup("Mom")
        val old = mom.keys.deviceId
        val job = mom.group.leaveAndForget()
        mom.awaitBaseline()
        job.cancel() // the kill: the leave job ends during the delivery wait, nothing was wiped
        job.join()
        assertTrue(mom.groups.exists())
        assertTrue(mom.identityResets.isEmpty())
        assertFalse(mom.resetMarker.exists())
        assertEquals(old, mom.keys.deviceId)
        mom.snapshots.invalidate()
        assertEquals(GroupState.Removed, withTimeout(5_000) { mom.group.state.first { it != GroupState.Loading && it != GroupState.Leaving } })
        mom.reset.launchResetIdentity(keepName = true).join() // RemovedRoute's «Начать заново»
        assertEquals(ResetStatus.Idle, mom.reset.status.value)
        assertFalse(mom.groups.exists())
        assertTrue(old != mom.keys.deviceId)
        assertEquals(1, mom.identityResets.size)
        assertEquals(Prefs(deviceName = "Mom"), mom.prefs.current())
        assertEquals(GroupState.NoGroup, withTimeout(5_000) { mom.group.state.first { it == GroupState.NoGroup } })
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

    @Test
    fun theOwnerCannotLeaveBeforeHandingTheStorageOver() = runBlocking<Unit> {
        val dad = TestDevice()
        try {
            mom.group.createGroup("Mom")
            dad.joinAsAdminOf(mom)
            mom.ownStorage()
            withTimeout(5_000) { mom.group.leaveAndForget().join() }
            assertEquals(LeaveError.OWNER_HANDOVER, mom.group.leaveError.value)
            assertEquals(0, mom.leftCount())
            assertTrue(mom.groups.exists())
        } finally {
            dad.close()
        }
    }

    @Test
    fun thePreviousOwnerLeavesOnceEveryPhoneSwitched() = runBlocking<Unit> {
        val dad = TestDevice()
        val kid = TestDevice()
        try {
            mom.group.createGroup("Mom")
            dad.joinAsAdminOf(mom)
            kid.joinGroupOf(mom)
            mom.ownStorage()
            dad.store().storeMembership(mom.store().membershipEntries())
            val g = dad.groups.read()!!
            // Dad took the storage to his Disk (Task 8 does this through the sign-in).
            checkNotNull(OwnerRecords.claim(dad.appendOwn, dad.store(), dad.keys, dad.cloudSecrets.ring(g), ByteArray(16) { 2 }, 2, "DAD1", dad.now + 1))
            mom.store().storeMembership(dad.store().membershipEntries())
            withTimeout(5_000) { mom.group.leaveAndForget().join() }
            assertEquals(LeaveError.CLOUD_SWITCHING, mom.group.leaveError.value)
            mom.group.dismissLeaveError()
            mom.store().applyBatch(listOf(dad.beat(2), kid.beat(2)))
            mom.sessions.value = SessionProgress(finished = 1, syncing = false)
            val job = mom.group.leaveAndForget()
            mom.awaitBaseline()
            mom.sessions.value = SessionProgress(finished = 2, syncing = false)
            withTimeout(5_000) { job.join() }
            assertNull(mom.group.leaveError.value)
            assertFalse(mom.groups.exists())
        } finally {
            dad.close()
            kid.close()
        }
    }
}
