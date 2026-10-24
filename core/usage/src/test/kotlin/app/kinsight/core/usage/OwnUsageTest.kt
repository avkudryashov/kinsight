package app.kinsight.core.usage

import app.kinsight.core.model.Departed
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Member
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnUsageTest {
    private val self = DeviceKeys.generate().deviceId
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "admin").groupId
    private val consented = UsageSettings(consentAtMs = 1_000L)

    private fun member(role: DeviceRole) = Member(self, ByteString.EMPTY, ByteString.EMPTY, "me", isAdmin = false, role = role)
    private fun active(role: DeviceRole) = MembershipState(groupId, mapOf(self to member(role)), emptyMap(), null, emptySet())

    @Test
    fun aConsentingChildMayShare() {
        assertTrue(ownUsageAllowed(active(DeviceRole.CHILD), self, consented))
    }

    @Test
    fun aChildWithoutConsentMayNot() {
        assertFalse(ownUsageAllowed(active(DeviceRole.CHILD), self, UsageSettings()))
    }

    @Test
    fun parentsAndRoleLessDevicesNever() {
        assertFalse(ownUsageAllowed(active(DeviceRole.PARENT), self, consented))
        assertFalse(ownUsageAllowed(active(DeviceRole.DEVICE_ROLE_UNSPECIFIED), self, consented))
    }

    @Test
    fun noGroupOrNotAnActiveMemberNever() {
        assertFalse(ownUsageAllowed(null, self, consented))
        assertFalse(ownUsageAllowed(active(DeviceRole.CHILD), DeviceKeys.generate().deviceId, consented))
        val departed = MembershipState(groupId, emptyMap(), mapOf(self to Departed(member(DeviceRole.CHILD), 5)), null, emptySet())
        assertFalse(ownUsageAllowed(departed, self, consented))
    }

    @Test
    fun onlyAnActiveParentMayKeepItsOwnTimeLocally() {
        assertTrue(ownTimeLocalAllowed(active(DeviceRole.PARENT), self))
        assertFalse(ownTimeLocalAllowed(active(DeviceRole.CHILD), self))
        assertFalse(ownTimeLocalAllowed(active(DeviceRole.DEVICE_ROLE_UNSPECIFIED), self))
        assertFalse(ownTimeLocalAllowed(null, self))
        assertFalse(ownTimeLocalAllowed(active(DeviceRole.PARENT), DeviceKeys.generate().deviceId))
        val departed = MembershipState(groupId, emptyMap(), mapOf(self to Departed(member(DeviceRole.PARENT), 5)), null, emptySet())
        assertFalse(ownTimeLocalAllowed(departed, self))
    }
}
