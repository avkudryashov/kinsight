package app.kinsight.feature.group

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.SnapshotViews

/** Who may do what on the group screen (spec §4.5, §6.6, §9.5). */
object GroupRules {
    fun canInvite(self: MemberView) = self.isAdmin

    /** Admin rights only for another parent, never for a child; revoking is final and not offered (Decisions). */
    fun canGrantAdmin(self: MemberView, m: MemberView) = self.isAdmin && !m.isSelf && m.role == Role.PARENT && !m.isAdmin

    fun canRemove(self: MemberView, m: MemberView) = self.isAdmin && !m.isSelf

    /** The only admin cannot leave others behind: nobody could invite any more (§6.6). Same rule GroupRepository applies. */
    fun canLeave(state: GroupState.Ready) = SnapshotViews.canLeave(state)

    /**
     * Spec §7.1: leaving turns the cloud off only for the storage owner with no other parent to take it; with one, the
     * leave is refused (LeaveError.OWNER_HANDOVER), so no warning. Same predicate as OwnerLifecycle.leaveBlock.
     */
    fun leaveTurnsCloudOff(state: GroupState.Ready) =
        state.selfOwnsStorage && state.members.none { !it.isSelf && it.role == Role.PARENT }

    fun needsSecondAdmin(self: MemberView, members: List<MemberView>) =
        self.isAdmin && members.count { it.isAdmin } == 1 && members.any { !it.isSelf && it.role == Role.PARENT }
}
