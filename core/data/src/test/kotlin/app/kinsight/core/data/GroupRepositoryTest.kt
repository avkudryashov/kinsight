package app.kinsight.core.data

import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun keyErrorYieldsKeyError() = runBlocking<Unit> {
        mom.identityBroken = KeyStorageException("device keys exist without a backend choice")
        assertIs<GroupState.KeyError>(mom.group.state.first { it !is GroupState.Loading })
    }

    @Test
    fun noGroupYieldsNoGroup() = runBlocking<Unit> {
        assertEquals(GroupState.NoGroup, mom.group.state.first { it !is GroupState.Loading })
    }
}
