package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertPlannerTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val dad = MemberView(DeviceId("bb".repeat(16)), "Dad", Role.PARENT, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val h = 3_600_000L

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun ev(author: MemberView, seq: Long, kind: StateEvent.Kind) = EventView(author.id, seq, kind, 0, 0, seq * 1_000, 0)
    private fun usage(m: MemberView, todayMs: Long) = DeviceUsage(
        m, DayTotal(LocalDate.of(2026, 10, 10), todayMs, 0),
        (6 downTo 0).map { DayTotal(LocalDate.of(2026, 10, 10).minusDays(it.toLong()), todayMs, 0) }, null, Sharing.On, null,
    )

    private fun input(
        self: MemberView = mom,
        members: List<MemberView> = listOf(mom, dad, kid),
        events: List<EventView> = emptyList(),
        now: Long = at(10, 12),
        markers: AlertMarkers = AlertMarkers(notifiedSeq = mapOf(kid.id to 0L), knownParents = setOf(mom.id)),
        mode: SummaryMode = SummaryMode.DAILY,
        children: List<DeviceUsage> = listOf(usage(kid, 2 * h)),
        departed: List<MemberView> = emptyList(),
    ) = AlertInput(self, members, departed, events, children, now, zone, 24, mode, markers)

    @Test
    fun firstRunMarksHistoryAsSeen() {
        val plan = AlertPlanner.plan(input(events = listOf(ev(kid, 3, StateEvent.Kind.SHARING_PAUSED)), markers = AlertMarkers()))
        assertTrue(plan.alerts.isEmpty())
        assertEquals(3L, plan.markers.notifiedSeq[kid.id])
    }

    @Test
    fun newIntegrityEventsOfAChildAreReportedOnce() {
        val events = listOf(
            ev(kid, 1, StateEvent.Kind.SHARING_RESUMED),
            ev(kid, 2, StateEvent.Kind.SHARING_PAUSED),
            ev(kid, 3, StateEvent.Kind.NOTIFICATION_HIDDEN),
            ev(dad, 7, StateEvent.Kind.SHARING_PAUSED), // a parent's events never alert
        )
        val first = AlertPlanner.plan(input(events = events))
        assertEquals(
            listOf(Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 2_000), Alert.Integrity("Kid", StateEvent.Kind.NOTIFICATION_HIDDEN, 3_000)),
            first.alerts,
        )
        assertTrue(AlertPlanner.plan(input(events = events, markers = first.markers)).alerts.isEmpty())
    }

    @Test
    fun aChildThatLeftIsReportedByName() {
        val plan = AlertPlanner.plan(input(members = listOf(mom, dad), departed = listOf(kid), events = listOf(ev(kid, 4, StateEvent.Kind.LEFT_GROUP)), children = emptyList()))
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.LEFT_GROUP, 4_000)), plan.alerts)
    }

    @Test
    fun noContactAfterNHoursOnceUntilSeenAgain() {
        val lost = kid.copy(lastSeenMs = at(9, 10))
        val first = AlertPlanner.plan(input(members = listOf(mom, lost)))
        assertEquals(listOf(Alert.NoContact("Kid", at(9, 10))), first.alerts.filterIsInstance<Alert.NoContact>())
        val again = AlertPlanner.plan(input(members = listOf(mom, lost), markers = first.markers))
        assertTrue(again.alerts.none { it is Alert.NoContact })
        val back = AlertPlanner.plan(input(members = listOf(mom, kid.copy(lastSeenMs = at(10, 11))), markers = again.markers))
        assertTrue(kid.id !in back.markers.noContact)
    }

    @Test
    fun dailySummaryAfterEightOncePerDay() {
        assertTrue(AlertPlanner.plan(input(now = at(10, 19))).alerts.none { it is Alert.Summary })
        val evening = AlertPlanner.plan(input(now = at(10, 21)))
        assertEquals(listOf(Alert.Summary(weekly = false, lines = listOf("Kid" to 2 * h))), evening.alerts.filterIsInstance<Alert.Summary>())
        assertTrue(AlertPlanner.plan(input(now = at(10, 22), markers = evening.markers)).alerts.none { it is Alert.Summary })
    }

    @Test
    fun weeklySummaryOnlyOnSundayAndOffNever() {
        // 2026-10-10 is a Saturday, 2026-10-11 a Sunday.
        assertTrue(AlertPlanner.plan(input(now = at(10, 21), mode = SummaryMode.WEEKLY)).alerts.none { it is Alert.Summary })
        val sunday = AlertPlanner.plan(input(now = at(11, 21), mode = SummaryMode.WEEKLY))
        assertEquals(listOf(Alert.Summary(weekly = true, lines = listOf("Kid" to 14 * h))), sunday.alerts.filterIsInstance<Alert.Summary>())
        assertTrue(AlertPlanner.plan(input(now = at(11, 21), mode = SummaryMode.OFF)).alerts.isEmpty())
        assertTrue(AlertPlanner.plan(input(now = at(10, 21), children = emptyList())).alerts.none { it is Alert.Summary })
    }

    @Test
    fun childIsToldAboutANewParent() {
        val kidSelf = kid.copy(isSelf = true)
        val first = AlertPlanner.plan(input(self = kidSelf, members = listOf(kidSelf, mom.copy(isSelf = false)), markers = AlertMarkers()))
        assertTrue(first.alerts.isEmpty())
        val withDad = AlertPlanner.plan(input(self = kidSelf, members = listOf(kidSelf, mom.copy(isSelf = false), dad), markers = first.markers))
        assertEquals(listOf(Alert.NewParent("Dad")), withDad.alerts)
    }
}
