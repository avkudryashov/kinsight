package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Member
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

sealed interface GroupState {
    data object Loading : GroupState

    data object NoGroup : GroupState

    data class KeyError(val message: String) : GroupState

    data class StorageError(val message: String) : GroupState

    /** An admin removed this device, or it left: only «start over» remains. */
    data object Removed : GroupState

    /**
     * This device signed LEFT_GROUP and waits (bounded) for a session to carry it before the local wipe (preflight
     * C5/C6): the root shows «Leaving: telling your family…», not the removal screen.
     */
    data object Leaving : GroupState

    /** [members]: active members, this device first, then parents, then children, each by name. */
    data class Ready(val groupId: GroupId, val self: MemberView, val members: List<MemberView>) : GroupState
}

/** [selfUsage] for a child (its own card), [children] for a parent; [parents]: who sees this device's data. */
data class DashboardState(
    val self: MemberView,
    val selfUsage: DeviceUsage?,
    val children: List<DeviceUsage>,
    val parents: List<MemberView>,
)

object SnapshotViews {
    /** [nowMs] (here and below): the app clock; peers' created_at_ms count at most MAX_CLOCK_SKEW_MS ahead of it. */
    fun groupState(s: GroupSnapshot, nowMs: Long = System.currentTimeMillis()): GroupState = when (s) {
        is GroupSnapshot.KeyError -> GroupState.KeyError(s.message)
        is GroupSnapshot.StorageError -> GroupState.StorageError(s.message)
        GroupSnapshot.NoGroup -> GroupState.NoGroup
        is GroupSnapshot.Ready -> if (s.removed) GroupState.Removed else {
            val members = members(s, nowMs)
            GroupState.Ready(s.groupId, members.first { it.isSelf }, members)
        }
    }

    /**
     * Whether this device may leave (spec §9.5): not as the only admin while other members remain, or the group would
     * be left without anyone to manage it. The group screen hides «Leave» then; GroupRepository refuses it as well.
     */
    fun canLeave(s: GroupSnapshot.Ready): Boolean {
        val active = s.membership.active.values
        val me = active.firstOrNull { it.id == s.self } ?: return false
        return !(me.isAdmin && active.count { it.isAdmin } == 1 && active.size > 1)
    }

    fun canLeave(s: GroupState.Ready): Boolean =
        !(s.self.isAdmin && s.members.count { it.isAdmin } == 1 && s.members.size > 1)

    fun members(s: GroupSnapshot.Ready, nowMs: Long = System.currentTimeMillis()): List<MemberView> =
        s.membership.active.values.map { view(s, it, nowMs) }
            .sortedWith(compareBy<MemberView>({ !it.isSelf }, { it.role != Role.PARENT }, { it.name }, { it.id }))

    fun dashboard(s: GroupSnapshot.Ready, today: LocalDate, nowMs: Long = System.currentTimeMillis()): DashboardState {
        val members = members(s, nowMs)
        val self = members.first { it.isSelf }
        val byAuthor = UsageReadModel.summaries(s.summaries)
        val events = UsageReadModel.events(s.events).groupBy { it.author }
        fun usage(m: MemberView) = UsageReadModel.usage(m, byAuthor[m.id], events[m.id].orEmpty(), today, nowMs = nowMs)
        return DashboardState(
            self = self,
            selfUsage = if (self.role == Role.CHILD) usage(self) else null,
            children = if (self.role == Role.PARENT) members.filter { it.role == Role.CHILD }.map(::usage) else emptyList(),
            parents = members.filter { it.role == Role.PARENT && !it.isSelf },
        )
    }

    /** Active or departed member [id]; null for an unknown device. */
    fun detail(
        s: GroupSnapshot.Ready,
        id: DeviceId,
        range: DetailRange,
        today: LocalDate,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        overrides: Map<String, AppCategory> = emptyMap(),
        details: List<DecodedEntry> = emptyList(),
        weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
    ): DeviceDetail? {
        val departed = s.membership.departed[id]
        val member = s.membership.active[id] ?: departed?.member ?: return null
        return UsageReadModel.detail(
            member = view(s, member, nowMs),
            byDate = UsageReadModel.summaries(s.summaries)[id],
            catalog = UsageReadModel.catalogOf(s.catalog, id),
            events = UsageReadModel.events(s.events).filter { it.author == id },
            range = range,
            today = today,
            departed = departed != null,
            zone = zone,
            overrides = overrides,
            details = UsageReadModel.details(details)[id],
            weekFields = weekFields,
            nowMs = nowMs,
        )
    }

    private fun view(s: GroupSnapshot.Ready, m: Member, nowMs: Long): MemberView {
        val isSelf = m.id == s.self
        val fresh = (s.summaries.asSequence() + s.heartbeats.asSequence() + s.events.asSequence())
            .filter { it.author == m.id }
            .maxOfOrNull { it.body.created_at_ms.coerceAtMost(nowMs + UsageReadModel.MAX_CLOCK_SKEW_MS) }
        val seen = listOfNotNull(s.peerSeen[m.id], fresh).maxOrNull()
        return MemberView(m.id, m.displayName, m.role.toRole(), m.isAdmin, isSelf, if (isSelf) null else seen)
    }
}
