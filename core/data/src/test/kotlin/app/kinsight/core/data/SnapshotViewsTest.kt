package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import app.kinsight.core.model.removeMemberEntry
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotViewsTest {
    private val momKeys = DeviceKeys.generate()
    private val dadKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val ops = listOf(
        genesis.entry,
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
    )
    private val today = LocalDate.of(2026, 10, 10)

    // The viewer's clock on [today]: never the wall clock, the child's own day (UTC) depends on it.
    private val noonMs = today.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    private val sum = kid.create { it.copy(usage_summary = UsageSummary(local_date = today.toString(), zone_id = "UTC", total_ms = 600_000)) }
    private val resumed = kid.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.SHARING_RESUMED)) }
    private val beat = kid.create { it.copy(heartbeat = Heartbeat(app_version = "t")) }

    private fun ready(self: DeviceKeys, extraOps: List<app.kinsight.core.model.DecodedEntry> = emptyList(), seen: Map<app.kinsight.core.model.DeviceId, Long> = emptyMap(), more: List<app.kinsight.core.model.DecodedEntry> = emptyList()) =
        GroupSnapshot.Ready(
            genesis.groupId, self.deviceId, Membership.compute(genesis.groupId, ops + extraOps)!!,
            summaries = listOf(sum) + more, catalog = emptyList(), events = listOf(resumed), heartbeats = listOf(beat), peerSeen = seen,
        )

    @Test
    fun membersAreOrderedSelfParentsChildrenWithLastSeen() {
        val members = SnapshotViews.members(ready(momKeys, seen = mapOf(kidKeys.deviceId to 5L)))
        assertEquals(listOf("Mom", "Dad", "Kid"), members.map { it.name })
        assertTrue(members[0].isSelf && members[0].isAdmin)
        assertEquals(Role.CHILD, members[2].role)
        assertEquals(maxOf(5L, beat.body.created_at_ms), members[2].lastSeenMs)
        assertNull(members[0].lastSeenMs)
    }

    /** Final fix item 6: a removed child, or one that signed LEFT_GROUP, is no longer an active member of its app screen. */
    @Test
    fun anAppOfADepartedOrLeftChildIsInactive() {
        val day = DetailRange(Period.DAY, today, today)
        assertTrue(SnapshotViews.app(ready(momKeys), kidKeys.deviceId, "com.x", day, today, noonMs)!!.active)
        val removal = mom.removeMemberEntry(kidKeys.deviceId, lastSeq = 0)
        assertFalse(SnapshotViews.app(ready(momKeys, listOf(removal)), kidKeys.deviceId, "com.x", day, today, noonMs)!!.active)
        val left = kid.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
        val leaving = ready(momKeys).let { r ->
            GroupSnapshot.Ready(r.groupId, r.self, r.membership, r.summaries, r.catalog, r.events + left, r.heartbeats, r.peerSeen)
        }
        assertFalse(SnapshotViews.app(leaving, kidKeys.deviceId, "com.x", day, today, noonMs)!!.active)
    }

    @Test
    fun removedSelfYieldsRemoved() {
        val removal = mom.removeMemberEntry(kidKeys.deviceId, lastSeq = 0)
        assertEquals(GroupState.Removed, SnapshotViews.groupState(ready(kidKeys, listOf(removal))))
    }

    @Test
    fun parentDashboardShowsChildrenOnly() {
        val d = SnapshotViews.dashboard(ready(momKeys), today, noonMs)
        assertEquals(listOf("Kid"), d.children.map { it.member.name })
        assertEquals(600_000L, d.children.single().today.totalMs)
        assertEquals(Sharing.On, d.children.single().sharing)
        assertNull(d.selfUsage)
    }

    @Test
    fun dashboardCardTodayIsTheChildsOwnDay() {
        // The child's clock (UTC) is already on 11 Oct while the viewer is still on the 10th.
        val next = kid.create { it.copy(usage_summary = UsageSummary(local_date = today.plusDays(1).toString(), zone_id = "UTC", total_ms = 777_000)) }
        val nowMs = today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 30 * 60_000L
        val r = ready(momKeys, more = listOf(next))
        val card = SnapshotViews.dashboard(r, today, nowMs).children.single()
        assertEquals(today.plusDays(1), card.today.date)
        assertEquals(777_000L, card.today.totalMs)
        val own = ready(kidKeys, more = listOf(next))
        assertEquals(today.plusDays(1), SnapshotViews.dashboard(own, today, nowMs).selfUsage!!.today.date)
    }

    @Test
    fun childDashboardShowsItselfAndWhoSeesIt() {
        val d = SnapshotViews.dashboard(ready(kidKeys), today, noonMs)
        assertTrue(d.children.isEmpty())
        assertEquals(600_000L, d.selfUsage!!.today.totalMs)
        assertEquals(listOf("Dad", "Mom"), d.parents.map { it.name }.sorted())
    }

    @Test
    fun detailOfAnUnknownDeviceIsNull() {
        val week = DetailRange.of(Period.WEEK, today, java.time.temporal.WeekFields.ISO)
        assertNull(SnapshotViews.detail(ready(momKeys), DeviceKeys.generate().deviceId, week, today))
        assertIs<DeviceDetail>(SnapshotViews.detail(ready(momKeys), kidKeys.deviceId, week, today))
    }

    @Test
    fun aPeerClockAheadCannotPushLastSeenOrUpdatedIntoTheFuture() {
        val now = 1_760_000_000_000L
        val ahead = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 10, clock = { now + 86_400_000L })
        val future = ahead.create { it.copy(usage_summary = UsageSummary(local_date = today.toString(), zone_id = "UTC", total_ms = 1)) }
        val r = GroupSnapshot.Ready(
            genesis.groupId, momKeys.deviceId, Membership.compute(genesis.groupId, ops)!!,
            summaries = listOf(future), catalog = emptyList(), events = emptyList(), heartbeats = emptyList(), peerSeen = emptyMap(),
        )
        val limit = now + UsageReadModel.MAX_CLOCK_SKEW_MS
        assertEquals(limit, SnapshotViews.members(r, now).single { it.name == "Kid" }.lastSeenMs)
        assertEquals(limit, SnapshotViews.dashboard(r, today, now).children.single().updatedMs)
    }

    @Test
    fun onlyTheSoleAdminOfOthersCannotLeave() {
        assertFalse(SnapshotViews.canLeave(ready(momKeys)))
        assertFalse(SnapshotViews.canLeave(SnapshotViews.groupState(ready(momKeys)) as GroupState.Ready))
        assertTrue(SnapshotViews.canLeave(ready(kidKeys)))
        assertTrue(SnapshotViews.canLeave(ready(momKeys, listOf(mom.grantAdminEntry(dadKeys.deviceId)))))
        val alone = GroupSnapshot.Ready(
            genesis.groupId, momKeys.deviceId, Membership.compute(genesis.groupId, listOf(genesis.entry))!!,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(),
        )
        assertTrue(SnapshotViews.canLeave(alone))
    }
}
