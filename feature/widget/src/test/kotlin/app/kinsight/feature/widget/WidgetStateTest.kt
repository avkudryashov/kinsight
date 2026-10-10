package app.kinsight.feature.widget

import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DeviceUsage
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetStateTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val mom = member("aa", "Mom", Role.PARENT, self = true)
    private val dad = member("bb", "Dad", Role.PARENT)

    private fun member(hex: String, name: String, role: Role, self: Boolean = false, seen: Long? = now) =
        MemberView(DeviceId(hex.repeat(16)), name, role, isAdmin = false, isSelf = self, lastSeenMs = if (self) null else seen)

    private fun usage(m: MemberView, minutes: Long, sharing: Sharing = Sharing.On, updated: Long? = now - 10 * 60_000) =
        DeviceUsage(m, DayTotal(today, minutes * 60_000, if (minutes > 0) 2 else 0), emptyList(), null, sharing, updated)

    private fun parentView(children: List<DeviceUsage>, selfUsage: DeviceUsage? = null) =
        DashboardState(self = mom, selfUsage = selfUsage, children = children, parents = listOf(dad))

    @Test
    fun noGroupIsEmpty() {
        assertEquals(WidgetState.Empty, WidgetStates.of(null, 24, now))
    }

    @Test
    fun parentWithoutChildren() {
        assertEquals(WidgetState.Parent(emptyList(), more = 0), WidgetStates.of(parentView(emptyList()), 24, now))
    }

    @Test
    fun parentWithOneChild() {
        val kid = member("cc", "Kid", Role.CHILD)
        val state = WidgetStates.of(parentView(listOf(usage(kid, 190))), 24, now)
        assertEquals(
            WidgetState.Parent(
                listOf(ChildRow(kid.id.hex, "Kid", 190 * 60_000, hasData = true, status = ChildStatus.Updated(now - 10 * 60_000))),
                more = 0,
            ),
            state,
        )
    }

    @Test
    fun parentWithFiveChildrenShowsFourAndMore() {
        val kids = listOf("c1", "c2", "c3", "c4", "c5").mapIndexed { i, hex -> usage(member(hex, "Kid$i", Role.CHILD), 10L * i) }
        val state = WidgetStates.of(parentView(kids), 24, now) as WidgetState.Parent
        assertEquals(listOf("Kid0", "Kid1", "Kid2", "Kid3"), state.children.map { it.name })
        assertEquals(1, state.more)
    }

    @Test
    fun pausedChildKeepsTimeAndSaysPaused() {
        val kid = member("cc", "Kid", Role.CHILD)
        val row = (WidgetStates.of(parentView(listOf(usage(kid, 30, Sharing.Paused(now - hour)))), 24, now) as WidgetState.Parent).children.single()
        assertEquals(ChildStatus.Paused(now - hour), row.status)
        assertEquals(30 * 60_000L, row.todayMs)
    }

    @Test
    fun childNotSeenLongerThanSettingHasNoContact() {
        val kid = member("cc", "Kid", Role.CHILD, seen = now - 7 * hour)
        val within = (WidgetStates.of(parentView(listOf(usage(kid, 30))), 24, now) as WidgetState.Parent).children.single()
        assertEquals(ChildStatus.Updated(now - 10 * 60_000), within.status)
        val past = (WidgetStates.of(parentView(listOf(usage(kid, 30))), 6, now) as WidgetState.Parent).children.single()
        assertEquals(ChildStatus.NoContact(now - 7 * hour), past.status)
    }

    @Test
    fun childWithoutSummaryHasNoData() {
        val kid = member("cc", "Kid", Role.CHILD)
        val row = (WidgetStates.of(parentView(listOf(usage(kid, 0, updated = null))), 24, now) as WidgetState.Parent).children.single()
        assertEquals(ChildStatus.NoData, row.status)
        assertEquals(false, row.hasData)
    }

    /** Play ruling (spec §4.8): only a consenting child's usage is ever shown. */
    @Test
    fun nonConsentingChildShowsNoTime() {
        val kid = member("cc", "Kid", Role.CHILD)
        val row = (WidgetStates.of(parentView(listOf(usage(kid, 30, Sharing.Never))), 24, now) as WidgetState.Parent).children.single()
        assertEquals(ChildStatus.NotSharing, row.status)
        assertEquals(0L, row.todayMs)
        assertEquals(false, row.hasData)
    }

    /** Review Focus 4: a child that left is not shown, stale. */
    @Test
    fun childThatLeftIsNotShown() {
        val kid = member("cc", "Kid", Role.CHILD)
        val gone = member("dd", "Gone", Role.CHILD)
        val state = WidgetStates.of(parentView(listOf(usage(kid, 30), usage(gone, 40, Sharing.Left))), 24, now) as WidgetState.Parent
        assertEquals(listOf("Kid"), state.children.map { it.name })
    }

    /** Play ruling: a parent's own usage never appears, even if a state carried it. */
    @Test
    fun parentsUsageIsExcluded() {
        val kid = member("cc", "Kid", Role.CHILD)
        val state = WidgetStates.of(parentView(listOf(usage(kid, 30), usage(dad, 500)), selfUsage = usage(mom, 600)), 24, now)
            as WidgetState.Parent
        assertEquals(listOf("Kid"), state.children.map { it.name })
    }

    @Test
    fun childSeesOwnTimeAndParents() {
        val me = member("cc", "Kid", Role.CHILD, self = true)
        val d = DashboardState(self = me, selfUsage = usage(me, 75), children = emptyList(), parents = listOf(mom.copy(isSelf = false), dad))
        assertEquals(WidgetState.Child(75 * 60_000, hasData = true, parents = listOf("Mom", "Dad")), WidgetStates.of(d, 24, now))
    }

    @Test
    fun childWithoutDataToday() {
        val me = member("cc", "Kid", Role.CHILD, self = true)
        val d = DashboardState(self = me, selfUsage = null, children = emptyList(), parents = emptyList())
        val state = WidgetStates.of(d, 24, now) as WidgetState.Child
        assertEquals(false, state.hasData)
        assertTrue(state.parents.isEmpty())
    }
}
