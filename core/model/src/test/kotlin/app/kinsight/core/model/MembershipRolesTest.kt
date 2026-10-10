package app.kinsight.core.model

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembershipRolesTest {
    private fun state(g: TestGroup, vararg more: DecodedEntry): MembershipState =
        Membership.compute(g.groupId, listOf(g.genesis.entry) + more)!!

    private fun EntryAuthor.add(keys: DeviceKeys, name: String, role: DeviceRole) =
        addMember(keys.sign.publicKey, keys.dh.publicKey, name, role)

    @Test
    fun rolesComeFromCreateAndAdd() {
        val g = TestGroup()
        val kid = DeviceKeys.generate()
        val dad = DeviceKeys.generate()
        val s = state(g, g.creator.add(kid, "kid", DeviceRole.CHILD), g.creator.add(dad, "dad", DeviceRole.PARENT))
        assertEquals(DeviceRole.PARENT, s.active.getValue(g.creatorKeys.deviceId).role)
        assertTrue(s.active.getValue(kid.deviceId).isChild)
        assertEquals(DeviceRole.PARENT, s.roleOf(dad.deviceId))
        assertFalse(s.active.getValue(dad.deviceId).isChild)
    }

    @Test
    fun childCannotBeMadeAdmin() {
        val g = TestGroup()
        val kid = DeviceKeys.generate()
        val add = g.creator.add(kid, "kid", DeviceRole.CHILD)
        val promote = g.creator.grantAdminEntry(kid.deviceId)
        val s = state(g, add, promote)
        assertFalse(s.active.getValue(kid.deviceId).isAdmin)
        assertFalse(promote in s.effectiveEntries)
    }

    @Test
    fun parentCanBeMadeAdmin() {
        val g = TestGroup()
        val dad = DeviceKeys.generate()
        val add = g.creator.add(dad, "dad", DeviceRole.PARENT)
        val promote = g.creator.grantAdminEntry(dad.deviceId)
        val s = state(g, add, promote)
        assertTrue(s.active.getValue(dad.deviceId).isAdmin)
    }

    @Test
    fun childGenesisIsInvalid() {
        val genesis = GroupGenesis.create(DeviceKeys.generate(), "kid", role = DeviceRole.CHILD)
        assertNull(Membership.compute(genesis.groupId, listOf(genesis.entry)))
    }

    @Test
    fun unspecifiedRoleIsNotChild() {
        val g = TestGroup()
        val other = DeviceKeys.generate()
        val s = state(g, g.creator.addMember(other)) // test fixture: no role field
        assertEquals(DeviceRole.DEVICE_ROLE_UNSPECIFIED, s.active.getValue(other.deviceId).role)
        assertFalse(s.active.getValue(other.deviceId).isChild)
    }

    @Test
    fun removeAndLeaveHelpersAreHonouredAndRolesSurvive() {
        val g = TestGroup()
        val kid = DeviceKeys.generate()
        val dad = DeviceKeys.generate()
        val addKid = g.creator.add(kid, "kid", DeviceRole.CHILD)
        val addDad = g.creator.add(dad, "dad", DeviceRole.PARENT)
        val remove = g.creator.removeMemberEntry(kid.deviceId, lastSeq = 0)
        val leave = g.newAuthor(dad).leaveGroupEntry()
        val s = state(g, addKid, addDad, remove, leave)
        assertEquals(setOf(g.creatorKeys.deviceId), s.active.keys)
        assertEquals(setOf(kid.deviceId, dad.deviceId), s.departed.keys)
        assertEquals(DeviceRole.CHILD, s.roleOf(kid.deviceId))
        assertEquals(StateEvent.Kind.LEFT_GROUP, leave.body.state_event!!.kind)
    }
}
