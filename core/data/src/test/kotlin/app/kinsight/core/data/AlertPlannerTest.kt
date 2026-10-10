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
        markers: AlertMarkers = AlertMarkers(knownParents = setOf(mom.id), initialized = true),
        mode: SummaryMode = SummaryMode.DAILY,
        children: List<DeviceUsage> = listOf(usage(kid, 2 * h)),
        departed: List<MemberView> = emptyList(),
    joined: Map<DeviceId, Long> = emptyMap(),
    inviter: DeviceId? = null,
) = AlertInput(self, members, departed, events, children, now, zone, 24, mode, markers, joined, inviter)

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
            listOf(Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 2_000, kid.id), Alert.Integrity("Kid", StateEvent.Kind.NOTIFICATION_HIDDEN, 3_000, kid.id)),
            first.alerts,
        )
        assertTrue(AlertPlanner.plan(input(events = events, markers = first.markers)).alerts.isEmpty())
    }

    @Test
    fun aChildThatLeftIsReportedByName() {
        val plan = AlertPlanner.plan(input(members = listOf(mom, dad), departed = listOf(kid), events = listOf(ev(kid, 4, StateEvent.Kind.LEFT_GROUP)), children = emptyList()))
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.LEFT_GROUP, 4_000, kid.id)), plan.alerts)
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
    fun noSummaryOnTheDayTheChildJoined() {
        // Paired at 20:14, after SUMMARY_HOUR: a "<1 min" summary right away would mislead.
        val joinedTonight = mapOf(kid.id to at(10, 20) + 14 * 60_000L)
        val evening = AlertPlanner.plan(input(now = at(10, 21), children = listOf(usage(kid, 30_000)), joined = joinedTonight))
        assertTrue(evening.alerts.none { it is Alert.Summary })
        // Nothing was posted, so the day is not used up either.
        assertEquals(null, evening.markers.lastSummaryDate)
        // The next evening the child is summarised as usual.
        val next = AlertPlanner.plan(input(now = at(11, 21), joined = joinedTonight, markers = evening.markers))
        assertEquals(listOf(Alert.Summary(weekly = false, lines = listOf("Kid" to 2 * h))), next.alerts.filterIsInstance<Alert.Summary>())
    }

    @Test
    fun aChildJoinedTodayIsLeftOutOfTheOthersSummary() {
        val sis = MemberView(DeviceId("dd".repeat(16)), "Sis", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
        val plan = AlertPlanner.plan(
            input(
                members = listOf(mom, kid, sis),
                now = at(10, 21),
                children = listOf(usage(kid, 2 * h), usage(sis, 60_000)),
                joined = mapOf(kid.id to at(1, 9), sis.id to at(10, 8)),
            ),
        )
        assertEquals(listOf(Alert.Summary(weekly = false, lines = listOf("Kid" to 2 * h))), plan.alerts.filterIsInstance<Alert.Summary>())
    }

    @Test
    fun summaryIsNotRepostedWhenUsageGrowsLaterTheSameDay() {
        val evening = AlertPlanner.plan(input(now = at(10, 20), children = listOf(usage(kid, h))))
        assertEquals(1, evening.alerts.filterIsInstance<Alert.Summary>().size)
        val later = AlertPlanner.plan(input(now = at(10, 23), children = listOf(usage(kid, 3 * h)), markers = evening.markers))
        assertTrue(later.alerts.none { it is Alert.Summary })
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

    @Test
    fun laterChildWithoutMarkerIsReportedFromZero() {
        val plan = AlertPlanner.plan(input(events = listOf(ev(kid, 1, StateEvent.Kind.SHARING_PAUSED))))
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 1_000, kid.id)), plan.alerts)
        assertEquals(1L, plan.markers.notifiedSeq[kid.id])
    }

    @Test
    fun childWithoutEventsThenFirstEventAlerts() {
        val first = AlertPlanner.plan(input(markers = AlertMarkers()))
        assertTrue(first.alerts.isEmpty())
        assertEquals(0L, first.markers.notifiedSeq[kid.id])
        val next = AlertPlanner.plan(input(events = listOf(ev(kid, 1, StateEvent.Kind.SYNC_STOPPED)), markers = first.markers))
        assertEquals(1, next.alerts.size)
    }

    @Test
    fun intervalEventsReportTheirStart() {
        val e = EventView(kid.id, 1, StateEvent.Kind.NOTIFICATION_HIDDEN, 5_000, 0, 9_000, 0)
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.NOTIFICATION_HIDDEN, 5_000, kid.id)), AlertPlanner.plan(input(events = listOf(e))).alerts)
    }

    @Test
    fun everyNonRecoveryKindAlerts() {
        val back = listOf("_RESTORED", "_RESUMED", "_SHOWN")
        for (kind in StateEvent.Kind.entries) {
            val expected = kind != StateEvent.Kind.KIND_UNSPECIFIED && back.none { kind.name.endsWith(it) }
            assertEquals(expected, kind in AlertPlanner.INTEGRITY_KINDS, kind.name)
            val alerts = AlertPlanner.plan(input(events = listOf(ev(kid, 1, kind)))).alerts
            assertEquals(expected, alerts.isNotEmpty(), kind.name)
        }
    }

    @Test
    fun noContactBoundaryAndNeverSeen() {
        val now = at(10, 12)
        fun plan(seen: Long?, joined: Map<DeviceId, Long> = emptyMap()) =
            AlertPlanner.plan(input(members = listOf(mom, kid.copy(lastSeenMs = seen)), now = now, joined = joined)).alerts.filterIsInstance<Alert.NoContact>()
        assertTrue(plan(now - 24 * h).isEmpty())
        assertEquals(1, plan(now - 24 * h - 1).size)
        assertTrue(plan(null).isEmpty())
        assertEquals(listOf(Alert.NoContact("Kid", now - 30 * h)), plan(null, mapOf(kid.id to now - 30 * h)))
        assertTrue(plan(null, mapOf(kid.id to now - 2 * h)).isEmpty())
    }

    @Test
    fun weeklySummaryOnDstEndSunday() {
        val sunday = AlertPlanner.plan(input(now = at(25, 21), mode = SummaryMode.WEEKLY))
        assertEquals(1, sunday.alerts.filterIsInstance<Alert.Summary>().size)
    }

    @Test
    fun childBaselineIsTheInvitingAdminOnly() {
        val kidSelf = kid.copy(isSelf = true)
        val members = listOf(kidSelf, mom.copy(isSelf = false), dad)
        val viaMom = AlertPlanner.plan(input(self = kidSelf, members = members, markers = AlertMarkers(), inviter = mom.id))
        assertEquals(listOf(Alert.NewParent("Dad")), viaMom.alerts)
        assertTrue(AlertPlanner.plan(input(self = kidSelf, members = members, markers = viaMom.markers, inviter = mom.id)).alerts.isEmpty())
    }
}
