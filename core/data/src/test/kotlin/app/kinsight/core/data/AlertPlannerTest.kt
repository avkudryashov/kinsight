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
    thresholds: List<ThresholdRule> = emptyList(),
    todays: Map<DeviceId, ThresholdDay> = emptyMap(),
) = AlertInput(self, members, departed, events, children, now, zone, 24, mode, markers, joined, inviter, thresholds, todays)

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
        assertEquals(listOf(Alert.NoContact("Kid", at(9, 10), kid.id)), first.alerts.filterIsInstance<Alert.NoContact>())
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
        assertEquals(listOf(Alert.NewParent("Dad", dad.id)), withDad.alerts)
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
        // The location kinds (protocol 3) and the blocking kinds (protocol 4) have no alert behaviour yet.
        val locationKinds = setOf(
            StateEvent.Kind.LOCATION_PERMISSION_OFF, StateEvent.Kind.LOCATION_DISABLED,
            StateEvent.Kind.ZONE_ENTER, StateEvent.Kind.ZONE_EXIT,
            StateEvent.Kind.BLOCKING_OFF, StateEvent.Kind.BLOCK_OVERRIDDEN,
        )
        for (kind in StateEvent.Kind.entries - locationKinds) {
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
        assertEquals(listOf(Alert.NoContact("Kid", now - 30 * h, kid.id)), plan(null, mapOf(kid.id to now - 30 * h)))
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
        assertEquals(listOf(Alert.NewParent("Dad", dad.id)), viaMom.alerts)
        assertTrue(AlertPlanner.plan(input(self = kidSelf, members = members, markers = viaMom.markers, inviter = mom.id)).alerts.isEmpty())
    }

    private val paused = StateEvent.Kind.SHARING_PAUSED
    private val resumed = StateEvent.Kind.SHARING_RESUMED

    @Test
    fun pauseThenResumeIsReportedThenResolved() {
        val first = AlertPlanner.plan(input(events = listOf(ev(kid, 1, paused))))
        assertEquals(listOf(Alert.Integrity("Kid", paused, 1_000, kid.id)), first.alerts)
        val events = listOf(ev(kid, 1, paused), ev(kid, 2, resumed))
        val second = AlertPlanner.plan(input(events = events, markers = first.markers))
        assertEquals(listOf(Alert.Resolved(kid.id, paused, "Kid")), second.alerts)
        assertTrue(AlertPlanner.plan(input(events = events, markers = second.markers)).alerts.isEmpty())
    }

    @Test
    fun pauseAndResumeBetweenTwoRunsAreBothSaidInOrder() {
        val plan = AlertPlanner.plan(input(events = listOf(ev(kid, 1, paused), ev(kid, 2, resumed))))
        assertEquals(listOf(Alert.Integrity("Kid", paused, 1_000, kid.id), Alert.Resolved(kid.id, paused, "Kid")), plan.alerts)
    }

    @Test
    fun aPauseAfterAResumeIsAFreshAlert() {
        val first = AlertPlanner.plan(input(events = listOf(ev(kid, 1, paused), ev(kid, 2, resumed))))
        val events = listOf(ev(kid, 1, paused), ev(kid, 2, resumed), ev(kid, 3, paused))
        val second = AlertPlanner.plan(input(events = events, markers = first.markers))
        assertEquals(listOf(Alert.Integrity("Kid", paused, 3_000, kid.id)), second.alerts)
        val all = AlertPlanner.plan(input(events = events))
        assertEquals(
            listOf(Alert.Integrity("Kid", paused, 1_000, kid.id), Alert.Resolved(kid.id, paused, "Kid"), Alert.Integrity("Kid", paused, 3_000, kid.id)),
            all.alerts,
        )
    }

    @Test
    fun aResumeWithoutAPauseSaysNothing() {
        assertTrue(AlertPlanner.plan(input(events = listOf(ev(kid, 1, resumed)))).alerts.isEmpty())
        val twice = listOf(ev(kid, 1, paused), ev(kid, 2, resumed), ev(kid, 3, resumed))
        assertEquals(1, AlertPlanner.plan(input(events = twice)).alerts.count { it is Alert.Resolved })
        // An unrelated kind in between does not close the pause, nor open one.
        val other = listOf(ev(kid, 1, StateEvent.Kind.NOTIFICATION_HIDDEN), ev(kid, 2, resumed))
        assertTrue(AlertPlanner.plan(input(events = other)).alerts.none { it is Alert.Resolved })
    }

    @Test
    fun hiddenThenShownIsResolved() {
        val hidden = StateEvent.Kind.NOTIFICATION_HIDDEN
        val first = AlertPlanner.plan(input(events = listOf(ev(kid, 1, hidden))))
        val second = AlertPlanner.plan(input(events = listOf(ev(kid, 1, hidden), ev(kid, 2, StateEvent.Kind.NOTIFICATION_SHOWN)), markers = first.markers))
        assertEquals(listOf(Alert.Resolved(kid.id, hidden, "Kid")), second.alerts)
    }

    @Test
    fun firstRunSaysNoResolved() {
        val plan = AlertPlanner.plan(input(events = listOf(ev(kid, 1, paused), ev(kid, 2, resumed)), markers = AlertMarkers()))
        assertTrue(plan.alerts.isEmpty())
        assertEquals(2L, plan.markers.notifiedSeq[kid.id])
    }

    @Test
    fun intervalKindsPostedAfterTheyEndAreNotResolved() {
        val kinds = listOf(StateEvent.Kind.SYNC_STOPPED, StateEvent.Kind.BLUETOOTH_OFF, StateEvent.Kind.NEARBY_PERMISSION_OFF, StateEvent.Kind.APP_FORCE_STOPPED)
        for (kind in kinds) {
            val alerts = AlertPlanner.plan(input(events = listOf(ev(kid, 1, kind), ev(kid, 2, resumed), ev(kid, 3, StateEvent.Kind.NOTIFICATION_SHOWN)))).alerts
            assertEquals(listOf<Alert>(Alert.Integrity("Kid", kind, 1_000, kid.id)), alerts, kind.name)
        }
    }

    // Thresholds (stage 5b, spec §9.7).

    private val min = 60_000L
    private val oct10 = LocalDate.of(2026, 10, 10)
    private fun rule(target: ThresholdTarget, limitMs: Long, child: DeviceId = kid.id) = ThresholdRule.of(child, target, limitMs)!!
    private fun day(totalMs: Long, games: Long = 0, apps: Map<String, Long> = emptyMap(), date: LocalDate = oct10, labels: Map<String, String> = emptyMap()) =
        ThresholdDay(date, TodayUsage(totalMs, if (games > 0) mapOf(AppCategory.GAMES to games) else emptyMap(), apps), labels)
    private fun thresholdAlerts(plan: AlertPlan) = plan.alerts.filterIsInstance<Alert.Threshold>()
    private fun byId(alerts: List<Alert.Threshold>) = alerts.sortedBy { ThresholdRule.idOf(it.child, it.target) }

    @Test
    fun thresholdFiresWhenReachedAndOncePerDay() {
        val total = rule(ThresholdTarget.Total, 3 * h)
        assertTrue(thresholdAlerts(AlertPlanner.plan(input(thresholds = listOf(total), todays = mapOf(kid.id to day(3 * h - min))))).isEmpty())
        val first = AlertPlanner.plan(input(thresholds = listOf(total), todays = mapOf(kid.id to day(3 * h))))
        assertEquals(listOf(Alert.Threshold(kid.id, "Kid", ThresholdTarget.Total, null, 3 * h, 3 * h)), thresholdAlerts(first))
        assertEquals(setOf("${total.id}@${3 * h}@2026-10-10"), first.markers.thresholdNotified)
        // Review Focus 3: the hourly runs later the same day, with more time, say nothing.
        var markers = first.markers
        for (hour in 13..23) {
            val again = AlertPlanner.plan(
                input(now = at(10, hour), thresholds = listOf(total), todays = mapOf(kid.id to day(3 * h + hour * min)), markers = markers),
            )
            assertTrue(thresholdAlerts(again).isEmpty())
            markers = again.markers
        }
        // The child's next day is a new promise.
        val next = AlertPlanner.plan(
            input(now = at(11, 20), thresholds = listOf(total), todays = mapOf(kid.id to day(4 * h, date = oct10.plusDays(1))), markers = markers),
        )
        assertEquals(1, thresholdAlerts(next).size)
    }

    @Test
    fun lateSummaryWithEveryLimitLongPassedNotifiesOncePerRule() {
        // Review Focus 3: the evening summary arrives with all three limits passed: one alert per rule, then silence.
        val rules = listOf(
            rule(ThresholdTarget.Total, 2 * h),
            rule(ThresholdTarget.Category(AppCategory.GAMES), h),
            rule(ThresholdTarget.App("com.roblox.client"), 30 * min),
        )
        val today = day(5 * h, games = 4 * h, apps = mapOf("com.roblox.client" to 4 * h), labels = mapOf("com.roblox.client" to "Roblox"))
        val evening = AlertPlanner.plan(input(now = at(10, 22), thresholds = rules, todays = mapOf(kid.id to today)))
        assertEquals(
            byId(
                listOf(
                    Alert.Threshold(kid.id, "Kid", ThresholdTarget.App("com.roblox.client"), "Roblox", 4 * h, 30 * min),
                    Alert.Threshold(kid.id, "Kid", ThresholdTarget.Category(AppCategory.GAMES), null, 4 * h, h),
                    Alert.Threshold(kid.id, "Kid", ThresholdTarget.Total, null, 5 * h, 2 * h),
                ),
            ),
            byId(thresholdAlerts(evening)),
        )
        val later = AlertPlanner.plan(input(now = at(10, 23), thresholds = rules, todays = mapOf(kid.id to today), markers = evening.markers))
        assertTrue(thresholdAlerts(later).isEmpty())
    }

    @Test
    fun firstRunStillFiresThresholdsAlreadyExceeded() {
        val plan = AlertPlanner.plan(input(markers = AlertMarkers(), thresholds = listOf(rule(ThresholdTarget.Total, h)), todays = mapOf(kid.id to day(2 * h))))
        assertEquals(1, thresholdAlerts(plan).size)
    }

    @Test
    fun raisedThenLoweredLimitDoesNotRepeatAndANewValueNotifiesWhenExceeded() {
        // Review Focus 2.
        val today = mapOf(kid.id to day(70 * min))
        val first = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, h)), todays = today))
        assertEquals(1, thresholdAlerts(first).size)
        val raised = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, 2 * h)), todays = today, markers = first.markers))
        assertTrue(thresholdAlerts(raised).isEmpty())
        val back = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, h)), todays = today, markers = raised.markers))
        assertTrue(thresholdAlerts(back).isEmpty())
        val lower = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, 45 * min)), todays = today, markers = back.markers))
        assertEquals(listOf(Alert.Threshold(kid.id, "Kid", ThresholdTarget.Total, null, 70 * min, 45 * min)), thresholdAlerts(lower))
        val again = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, 45 * min)), todays = today, markers = lower.markers))
        assertTrue(thresholdAlerts(again).isEmpty())
    }

    @Test
    fun aDeletedRuleNotifiesAgainWhenAddedBack() {
        val total = rule(ThresholdTarget.Total, h)
        val today = mapOf(kid.id to day(2 * h))
        val first = AlertPlanner.plan(input(thresholds = listOf(total), todays = today))
        val deleted = AlertPlanner.plan(input(thresholds = emptyList(), todays = today, markers = first.markers))
        assertTrue(deleted.markers.thresholdNotified.isEmpty())
        assertEquals(1, thresholdAlerts(AlertPlanner.plan(input(thresholds = listOf(total), todays = today, markers = deleted.markers))).size)
    }

    @Test
    fun categoryOverrideChangedAfterTheAlertDoesNotRepeatIt() {
        // Review Focus 5: an override moves the games away and back the same day.
        val games = rule(ThresholdTarget.Category(AppCategory.GAMES), h)
        val first = AlertPlanner.plan(input(thresholds = listOf(games), todays = mapOf(kid.id to day(2 * h, games = 90 * min))))
        assertEquals(1, thresholdAlerts(first).size)
        val moved = AlertPlanner.plan(input(thresholds = listOf(games), todays = mapOf(kid.id to day(2 * h)), markers = first.markers))
        assertTrue(thresholdAlerts(moved).isEmpty())
        val back = AlertPlanner.plan(input(thresholds = listOf(games), todays = mapOf(kid.id to day(2 * h, games = 95 * min)), markers = moved.markers))
        assertTrue(thresholdAlerts(back).isEmpty())
    }

    @Test
    fun parentsUsageNeverAndADepartedChildNever() {
        val forDad = rule(ThresholdTarget.Total, h, child = dad.id)
        val forMom = rule(ThresholdTarget.Total, h, child = mom.id)
        val forKid = rule(ThresholdTarget.Total, h)
        val todays = mapOf(dad.id to day(5 * h), mom.id to day(5 * h), kid.id to day(5 * h))
        assertTrue(thresholdAlerts(AlertPlanner.plan(input(thresholds = listOf(forDad, forMom), todays = todays))).isEmpty())
        val departed = input(members = listOf(mom, dad), departed = listOf(kid), children = emptyList(), thresholds = listOf(forKid), todays = todays)
        assertTrue(thresholdAlerts(AlertPlanner.plan(departed)).isEmpty())
    }

    @Test
    fun aChildsOwnPhoneNeverEvaluatesThresholds() {
        val me = kid.copy(isSelf = true)
        val plan = AlertPlanner.plan(
            input(self = me, members = listOf(mom.copy(isSelf = false), me), thresholds = listOf(rule(ThresholdTarget.Total, h)), todays = mapOf(kid.id to day(5 * h))),
        )
        assertTrue(thresholdAlerts(plan).isEmpty())
    }

    @Test
    fun noUsageForTheChildsTodayIsNothingToEvaluate() {
        val plan = AlertPlanner.plan(input(thresholds = listOf(rule(ThresholdTarget.Total, h)), todays = mapOf(kid.id to ThresholdDay(oct10, null))))
        assertTrue(thresholdAlerts(plan).isEmpty())
    }

    @Test
    fun thresholdMarkersKeepTheLastThreeDates() {
        val total = rule(ThresholdTarget.Total, h)
        val old = listOf(6, 7, 8, 9).map { "${total.id}@$h@2026-10-0$it" }.toSet()
        val plan = AlertPlanner.plan(
            input(thresholds = listOf(total), todays = mapOf(kid.id to day(2 * h)), markers = AlertMarkers(initialized = true, thresholdNotified = old)),
        )
        assertEquals(setOf("2026-10-08", "2026-10-09", "2026-10-10"), plan.markers.thresholdNotified.map { it.substringAfterLast('@') }.toSet())
    }

    @Test
    fun intervalAlertsCarryTheirLength() {
        val bt = EventView(kid.id, 1, StateEvent.Kind.BLUETOOTH_OFF, 5_000, 65_000, 70_000, 0)
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.BLUETOOTH_OFF, 5_000, kid.id, durationMs = 60_000)), AlertPlanner.plan(input(events = listOf(bt))).alerts)
        val open = EventView(kid.id, 1, StateEvent.Kind.NOTIFICATION_HIDDEN, 5_000, 0, 9_000, 0)
        assertEquals(null, (AlertPlanner.plan(input(events = listOf(open))).alerts.single() as Alert.Integrity).durationMs)
    }

    @Test
    fun levelsSeparateWhatNeedsAParentFromWhatDoesNot() {
        val min = 60_000L
        fun integrity(kind: StateEvent.Kind, ms: Long? = null) = Alert.Integrity("Kid", kind, 0, kid.id, ms)
        val important = AlertPlanner.INTEGRITY_KINDS - AlertPlanner.MINOR_KINDS - StateEvent.Kind.BLUETOOTH_OFF
        important.forEach { assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(integrity(it)), it.name) }
        AlertPlanner.MINOR_KINDS.forEach { assertEquals(AlertLevel.MINOR, AlertPlanner.level(integrity(it)), it.name) }
        assertEquals(AlertLevel.MINOR, AlertPlanner.level(integrity(StateEvent.Kind.BLUETOOTH_OFF, 29 * min)))
        assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(integrity(StateEvent.Kind.BLUETOOTH_OFF, 30 * min)))
        assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(integrity(StateEvent.Kind.BLUETOOTH_OFF)), "unknown length: say it")
        assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(Alert.NoContact("Kid", 0, kid.id)))
        assertEquals(AlertLevel.TRANSPARENCY, AlertPlanner.level(Alert.NewParent("Dad", dad.id)))
        assertEquals(AlertLevel.SUMMARY, AlertPlanner.level(Alert.Summary(false, emptyList())))
        assertEquals(AlertLevel.THRESHOLD, AlertPlanner.level(Alert.Threshold(kid.id, "Kid", ThresholdTarget.Total, null, 2, 1)))
        assertEquals(setOf(StateEvent.Kind.CLOCK_CHANGED, StateEvent.Kind.DATA_GAP, StateEvent.Kind.ZONE_ENTER), AlertPlanner.MINOR_KINDS)
    }

    @Test
    fun theKnownParentsAreTheInviterOnTheFirstPassAndTheToldOnesAfter() {
        val members = listOf(mom.copy(isSelf = false), dad, kid.copy(isSelf = true))
        val self = kid.copy(isSelf = true)
        assertEquals(setOf(mom.id), AlertPlanner.knownParents(input(self = self, members = members, markers = AlertMarkers(), inviter = mom.id)))
        assertEquals(setOf(mom.id, dad.id), AlertPlanner.knownParents(input(self = self, members = members, markers = AlertMarkers())), "unknown inviter: all")
        assertEquals(setOf(dad.id), AlertPlanner.knownParents(input(self = self, members = members, markers = AlertMarkers(knownParents = setOf(dad.id), initialized = true))))
    }

    @Test
    fun openProblemsAreTheAnnouncedUnresolvedPausesAndHiddenNotifications() {
        fun e(seq: Long, kind: StateEvent.Kind) = ev(kid, seq, kind)
        fun told(seq: Long) = AlertMarkers(knownParents = setOf(mom.id), initialized = true, notifiedSeq = mapOf(kid.id to seq))
        val pauseAgain = listOf(e(1, StateEvent.Kind.SHARING_PAUSED), e(2, StateEvent.Kind.SHARING_RESUMED), e(3, StateEvent.Kind.SHARING_PAUSED))
        assertEquals(listOf(Alert.Integrity("Kid", StateEvent.Kind.SHARING_PAUSED, 3_000, kid.id)), AlertPlanner.openProblems(input(events = pauseAgain, markers = told(3))))
        assertEquals(emptyList(), AlertPlanner.openProblems(input(events = pauseAgain, markers = told(2))), "not said yet: the plan says it, with sound")
        assertEquals(emptyList(), AlertPlanner.openProblems(input(events = pauseAgain.take(2), markers = told(2))))
        assertEquals(
            listOf(StateEvent.Kind.NOTIFICATION_HIDDEN),
            AlertPlanner.openProblems(input(events = listOf(e(1, StateEvent.Kind.NOTIFICATION_HIDDEN), e(2, StateEvent.Kind.BLUETOOTH_OFF)), markers = told(2))).map { it.kind },
        )
    }

    @Test
    fun aChildsZoneEventsAreAnnouncedWithTheZoneLeavingLoudly() {
        val exit = EventView(kid.id, 5, StateEvent.Kind.ZONE_EXIT, at(10, 11), 0, at(10, 11), 0, zoneId = "aa".repeat(16), zoneName = "Home")
        val enter = EventView(kid.id, 6, StateEvent.Kind.ZONE_ENTER, at(10, 12), 0, at(10, 12), 0, zoneId = "aa".repeat(16), zoneName = null)
        val plan = AlertPlanner.plan(input(events = listOf(exit, enter)))
        assertEquals(
            listOf(
                Alert.Integrity("Kid", StateEvent.Kind.ZONE_EXIT, at(10, 11), kid.id, null, "Home"),
                Alert.Integrity("Kid", StateEvent.Kind.ZONE_ENTER, at(10, 12), kid.id, null, null),
            ),
            plan.alerts.filterIsInstance<Alert.Integrity>(),
        )
        assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(plan.alerts[0]))
        assertEquals(AlertLevel.MINOR, AlertPlanner.level(plan.alerts[1]))
        assertTrue(AlertPlanner.plan(input(events = listOf(exit, enter), markers = plan.markers)).alerts.none { it is Alert.Integrity })
        assertTrue(StateEvent.Kind.ZONE_EXIT !in AlertPlanner.INTEGRITY_KINDS, "zone news is not an integrity signal (IntegrityFastSync)")
    }

    private fun loc(seq: Long, kind: StateEvent.Kind, from: Long, to: Long) = EventView(kid.id, seq, kind, from, to, seq * 1_000, 0)

    @Test
    fun aLocationProblemIsAnnouncedAtOnceAndResolvedWhenBack() {
        val off = StateEvent.Kind.LOCATION_PERMISSION_OFF
        val first = AlertPlanner.plan(input(events = listOf(loc(1, off, 5_000, 5_000))))
        assertEquals(listOf<Alert>(Alert.Integrity("Kid", off, 5_000, kid.id)), first.alerts)
        assertEquals(AlertLevel.IMPORTANT, AlertPlanner.level(first.alerts.single()))
        val second = AlertPlanner.plan(input(events = listOf(loc(1, off, 5_000, 5_000), loc(2, off, 5_000, 65_000)), markers = first.markers))
        assertEquals(listOf<Alert>(Alert.Resolved(kid.id, off, "Kid")), second.alerts)
        // Never seen open (both came late, the open one pruned): the interval itself is the news, with its length.
        val disabled = StateEvent.Kind.LOCATION_DISABLED
        val late = AlertPlanner.plan(input(events = listOf(loc(3, disabled, 7_000, 67_000))))
        assertEquals(listOf<Alert>(Alert.Integrity("Kid", disabled, 7_000, kid.id, durationMs = 60_000)), late.alerts)
    }

    @Test
    fun anOpenLocationProblemIsRepostedLikeAPause() {
        val disabled = StateEvent.Kind.LOCATION_DISABLED
        val markers = AlertMarkers(knownParents = setOf(mom.id), initialized = true, notifiedSeq = mapOf(kid.id to 1L))
        assertEquals(listOf(Alert.Integrity("Kid", disabled, 5_000, kid.id)), AlertPlanner.openProblems(input(events = listOf(loc(1, disabled, 5_000, 5_000)), markers = markers)))
        val closed = listOf(loc(1, disabled, 5_000, 5_000), loc(2, disabled, 5_000, 9_000))
        assertEquals(emptyList(), AlertPlanner.openProblems(input(events = closed, markers = markers.copy(notifiedSeq = mapOf(kid.id to 2L)))))
    }

    @Test
    fun aShortLocationBlackoutInOneBatchIsAnnouncedWithItsLength() {
        val off = StateEvent.Kind.LOCATION_PERMISSION_OFF
        val markers = AlertMarkers(knownParents = setOf(mom.id), initialized = true)
        val plan = AlertPlanner.plan(input(events = listOf(loc(1, off, 5_000, 5_000), loc(2, off, 5_000, 65_000)), markers = markers))
        assertEquals(listOf<Alert>(Alert.Integrity("Kid", off, 5_000, kid.id, durationMs = 60_000)), plan.alerts)
    }

    @Test
    fun aFlappingLocationSwitchResolvesTheFirstAndAnnouncesTheNext() {
        val off = StateEvent.Kind.LOCATION_DISABLED
        val first = AlertPlanner.plan(input(events = listOf(loc(1, off, 5_000, 5_000))))
        assertEquals(listOf<Alert>(Alert.Integrity("Kid", off, 5_000, kid.id)), first.alerts)
        val events = listOf(loc(1, off, 5_000, 5_000), loc(2, off, 5_000, 65_000), loc(3, off, 70_000, 70_000))
        val second = AlertPlanner.plan(input(events = events, markers = first.markers))
        assertEquals(listOf(Alert.Resolved(kid.id, off, "Kid"), Alert.Integrity("Kid", off, 70_000, kid.id)), second.alerts)
        // All three new in one pass: the closed interval with its length, then the open one.
        val fresh = AlertMarkers(knownParents = setOf(mom.id), initialized = true)
        assertEquals(
            listOf<Alert>(Alert.Integrity("Kid", off, 5_000, kid.id, durationMs = 60_000), Alert.Integrity("Kid", off, 70_000, kid.id)),
            AlertPlanner.plan(input(events = events, markers = fresh)).alerts,
        )
        assertEquals(listOf(Alert.Integrity("Kid", off, 70_000, kid.id)), AlertPlanner.openProblems(input(events = events, markers = second.markers)))
    }
}
