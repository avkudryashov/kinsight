package app.kinsight.feature.group

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupRulesTest {
    private fun m(hex: String, role: Role, admin: Boolean, self: Boolean = false) =
        MemberView(DeviceId(hex.repeat(16)), hex, role, admin, self, null)

    private val mom = m("aa", Role.PARENT, admin = true, self = true)
    private val dad = m("bb", Role.PARENT, admin = false)
    private val kid = m("cc", Role.CHILD, admin = false)

    private fun ready(self: MemberView, members: List<MemberView>) = GroupState.Ready(GroupId("dd".repeat(16)), self, members)

    @Test
    fun adminActions() {
        assertTrue(GroupRules.canInvite(mom))
        assertFalse(GroupRules.canInvite(kid))
        assertFalse(GroupRules.canInvite(dad))
        assertTrue(GroupRules.canGrantAdmin(mom, dad))
        assertFalse(GroupRules.canGrantAdmin(mom, kid)) // a child is never an admin (spec §4.5)
        assertFalse(GroupRules.canGrantAdmin(mom, dad.copy(isAdmin = true)))
        assertFalse(GroupRules.canGrantAdmin(dad, kid.copy(role = Role.PARENT)))
        assertTrue(GroupRules.canRemove(mom, kid))
        assertFalse(GroupRules.canRemove(mom, mom))
        assertFalse(GroupRules.canRemove(dad, kid))
    }

    @Test
    fun soleAdminCannotLeaveOthersBehind() {
        assertFalse(GroupRules.canLeave(ready(mom, listOf(mom, dad, kid))))
        assertTrue(GroupRules.canLeave(ready(mom, listOf(mom))))
        val kidSelf = kid.copy(isSelf = true)
        assertTrue(GroupRules.canLeave(ready(kidSelf, listOf(mom.copy(isSelf = false), kidSelf))))
        assertTrue(GroupRules.canLeave(ready(mom, listOf(mom, dad.copy(isAdmin = true)))))
    }

    @Test
    fun secondAdminIsSuggestedWhenAnotherParentExists() {
        assertTrue(GroupRules.needsSecondAdmin(mom, listOf(mom, dad, kid)))
        assertFalse(GroupRules.needsSecondAdmin(mom, listOf(mom, kid)))
        assertFalse(GroupRules.needsSecondAdmin(mom, listOf(mom, dad.copy(isAdmin = true))))
        assertFalse(GroupRules.needsSecondAdmin(dad.copy(isSelf = true), listOf(mom.copy(isSelf = false), dad.copy(isSelf = true))))
    }
}
