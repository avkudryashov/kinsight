package app.kinsight.feature.onboarding

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsentRecipientsTest {
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId

    private fun member(name: String, role: Role, self: Boolean = false) =
        MemberView(DeviceId.fromSignPublicKey(DeviceKeys.generate().sign.publicKey), name, role, isAdmin = false, isSelf = self, lastSeenMs = null)

    private fun ready(self: MemberView, vararg others: MemberView) = GroupState.Ready(groupId, self, listOf(self) + others)

    @Test
    fun aChildSeesItsParentsOnly() {
        val kid = member("Kid", Role.CHILD, self = true)
        val state = ready(kid, member("Mom", Role.PARENT), member("Dad", Role.PARENT), member("Sis", Role.CHILD))
        assertEquals("Mom, Dad", consentRecipients(state))
    }

    @Test
    fun aParentNeverGetsTheConsentScreen() {
        val mom = member("Mom", Role.PARENT, self = true)
        assertNull(consentRecipients(ready(mom, member("Kid", Role.CHILD))))
        assertNull(consentRecipients(GroupState.Loading))
        assertNull(consentRecipients(GroupState.NoGroup))
    }
}
