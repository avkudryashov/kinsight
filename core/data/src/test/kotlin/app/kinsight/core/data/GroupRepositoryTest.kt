package app.kinsight.core.data

import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.transport.cloud.GroupKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupRepositoryTest {
    private val mom = TestDevice()

    @AfterTest
    fun cleanUp() = mom.close()

    @Test
    fun createGroupMakesThisDeviceAParentAdmin() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val state = assertIs<GroupState.Ready>(mom.group.state.first { it !is GroupState.Loading && it !is GroupState.NoGroup })
        assertEquals("Mom", state.self.name)
        assertTrue(state.self.isAdmin)
        assertEquals(Role.PARENT, state.self.role)
        assertEquals(listOf("start:false"), mom.sync.calls)
        assertFailsWith<IllegalStateException> { mom.group.createGroup("again") }
    }

    @Test
    fun adminOpsAreSignedAndSyncedFast() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val dad = DeviceKeys.generate()
        val kid = DeviceKeys.generate()
        mom.pipeline.appendOwn { a ->
            listOf(
                a.addMember(dad.sign.publicKey, dad.dh.publicKey, "Dad", DeviceRole.PARENT),
                a.addMember(kid.sign.publicKey, kid.dh.publicKey, "Kid", DeviceRole.CHILD),
            )
        }
        mom.group.grantAdmin(dad.deviceId)
        mom.group.remove(kid.deviceId)
        val ready = assertIs<GroupState.Ready>(mom.group.state.first { it is GroupState.Ready })
        assertEquals(listOf("Mom", "Dad"), ready.members.map { it.name })
        assertTrue(ready.members[1].isAdmin)
        assertEquals(listOf("start:false", "start:true", "start:true"), mom.sync.calls)
    }

    @Test
    fun leaveSignsLeftGroup() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.group.leave()
        assertTrue(mom.store().stateEvents().any { it.body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP })
        assertEquals(EntryKind.STATE_EVENT, mom.store().membershipEntries().last().kind)
    }

    @Test
    fun leaveStartsSyncAsTheUserSoAStopDoesNotSwallowLeftGroup() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        mom.group.leave()
        // Leaving is an explicit user action: it must clear a user's Stop (startByUser), then go fast.
        assertEquals(listOf("start:false", "startByUser", "start:true"), mom.sync.calls)
    }

    @Test
    fun keyErrorYieldsKeyError() = runBlocking<Unit> {
        mom.identityBroken = KeyStorageException("device keys exist without a backend choice")
        assertIs<GroupState.KeyError>(mom.group.state.first { it !is GroupState.Loading })
    }

    @Test
    fun noGroupYieldsNoGroup() = runBlocking<Unit> {
        assertEquals(GroupState.NoGroup, mom.group.state.first { it !is GroupState.Loading })
    }

    @Test
    fun removingAPhoneChangesTheGroupKeyEvenWithTheCloudOff() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kid = DeviceKeys.generate()
        check(mom.pipeline.appendOwn { a -> listOf(a.addMember(kid.sign.publicKey, kid.dh.publicKey, "Kid", DeviceRole.CHILD)) })
        mom.group.remove(kid.deviceId)
        val m = Membership.compute(mom.groups.read()!!.groupId, mom.store().membershipEntries())!!
        assertEquals(1, m.discoveryRotation!!.epoch, "the privacy policy: the key changes when a phone is removed")
        assertFalse(m.discoveryRotation!!.keys.any { it.device_id == kid.deviceId.bytes().toByteString() })
        assertFalse(GroupKey.rotationNeeded(m))
    }

    @Test
    fun theGroupStateSaysWhoHoldsTheStorage() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        assertFalse((SnapshotViews.groupState(mom.snapshots.load(emptyMap())) as GroupState.Ready).selfOwnsStorage)
        mom.ownStorage()
        assertTrue((SnapshotViews.groupState(mom.snapshots.load(emptyMap())) as GroupState.Ready).selfOwnsStorage)
    }

    @Test
    fun eachRemovalTakesTheNextKeyGeneration() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kid = DeviceKeys.generate()
        val grandma = DeviceKeys.generate()
        check(mom.pipeline.appendOwn { a -> listOf(a.addMember(kid.sign.publicKey, kid.dh.publicKey, "Kid", DeviceRole.CHILD)) })
        check(mom.pipeline.appendOwn { a -> listOf(a.addMember(grandma.sign.publicKey, grandma.dh.publicKey, "Grandma", DeviceRole.PARENT)) })
        mom.group.remove(kid.deviceId)
        mom.group.remove(grandma.deviceId)
        val m = Membership.compute(mom.groups.read()!!.groupId, mom.store().membershipEntries())!!
        assertEquals(2, m.discoveryRotation!!.epoch)
        assertEquals(listOf(mom.keys.deviceId.bytes().toByteString()), m.discoveryRotation!!.keys.map { it.device_id })
        assertFalse(GroupKey.rotationNeeded(m))
    }

    @Test
    fun removalWaitsForARunningCloudRun() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val kid = DeviceKeys.generate()
        check(mom.pipeline.appendOwn { a -> listOf(a.addMember(kid.sign.publicKey, kid.dh.publicKey, "Kid", DeviceRole.CHILD)) })
        val before = mom.store().membershipEntries().size
        mom.cloudSecrets.runLock.lock()
        val removal = launch { mom.group.remove(kid.deviceId) }
        delay(200)
        assertEquals(before, mom.store().membershipEntries().size, "nothing signed while a cloud run holds runLock")
        mom.cloudSecrets.runLock.unlock()
        withTimeout(5_000) { removal.join() }
        assertEquals(before + 2, mom.store().membershipEntries().size, "RemoveMember and the rotation")
    }
}
