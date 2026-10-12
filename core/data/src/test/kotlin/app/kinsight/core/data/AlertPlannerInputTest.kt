package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AlertPlanner.input from a snapshot: thresholds read the child's own day, summaries the viewer's (spec §9.3, §9.7). */
class AlertPlannerInputTest {
    private val momKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val utc = ZoneId.of("UTC")
    private val min = 60_000L
    private val h = 60 * min
    private val oct10 = LocalDate.of(2026, 10, 10)
    private val longAgo = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, clock = { longAgo }, lastLamport = genesis.entry.lamport)
    private val ops = listOf(genesis.entry, mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD))
    private val roblox = "com.roblox.client"

    private fun at(zone: ZoneId, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun summaries(createdMs: Long, zone: String, vararg days: Pair<LocalDate, Long>): List<DecodedEntry> {
        val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0, clock = { createdMs })
        return days.map { (d, ms) ->
            kid.create { it.copy(usage_summary = UsageSummary(local_date = d.toString(), zone_id = zone, total_ms = ms, apps = listOf(AppTotal(roblox, ms)))) }
        }
    }

    private fun ready(summaries: List<DecodedEntry>) = GroupSnapshot.Ready(
        genesis.groupId, momKeys.deviceId, Membership.compute(genesis.groupId, ops)!!,
        summaries = summaries,
        catalog = listOf(EntryAuthor(genesis.groupId, kidKeys, lastSeq = 50, clock = { longAgo }).create { it.copy(app_catalog = AppCatalog(package_name = roblox, label = "Roblox")) }),
        events = emptyList(), heartbeats = emptyList(), peerSeen = emptyMap(),
    )

    private fun rule(target: ThresholdTarget, limitMs: Long) = ThresholdRule.of(kidKeys.deviceId, target, limitMs)!!

    @Test
    fun aChildAheadOfTheParentIsJudgedOnItsOwnDayButSummarisedOnTheParents() {
        // 20:30 UTC for the parent: the child in Kiritimati (UTC+14) is already on the 11th, with 20 minutes so far.
        val now = at(utc, 10, 20, 30)
        val snapshot = ready(summaries(now - h, "Pacific/Kiritimati", oct10 to 5 * h, oct10.plusDays(1) to 20 * min))
        val rules = listOf(rule(ThresholdTarget.Total, h), rule(ThresholdTarget.App(roblox), 15 * min))
        val input = AlertPlanner.input(snapshot, Prefs(), AlertMarkers(initialized = true), now, utc, rules)
        val plan = AlertPlanner.plan(input)
        // The child's yesterday (five hours) never trips today's one-hour limit; its 20 minutes trip the app's 15.
        assertEquals(
            listOf(Alert.Threshold(kidKeys.deviceId, "Kid", ThresholdTarget.App(roblox), "Roblox", 20 * min, 15 * min)),
            plan.alerts.filterIsInstance<Alert.Threshold>(),
        )
        assertEquals(setOf("${rules[1].id}@${15 * min}@2026-10-11"), plan.markers.thresholdNotified)
        // The parent's 20:00 summary is about the parent's day, not the child's near-empty new one.
        assertEquals(listOf(Alert.Summary(weekly = false, lines = listOf("Kid" to 5 * h))), plan.alerts.filterIsInstance<Alert.Summary>())
    }

    @Test
    fun theWeeklySummaryIsTheParentsLastSevenDays() {
        val now = ZonedDateTime.of(2026, 10, 11, 20, 30, 0, 0, utc).toInstant().toEpochMilli() // a Sunday
        val snapshot = ready(summaries(now - h, "Pacific/Kiritimati", oct10 to 5 * h, oct10.plusDays(1) to 2 * h, oct10.plusDays(2) to 10 * min))
        val plan = AlertPlanner.plan(AlertPlanner.input(snapshot, Prefs(summaryMode = SummaryMode.WEEKLY), AlertMarkers(initialized = true), now, utc))
        assertEquals(listOf(Alert.Summary(weekly = true, lines = listOf("Kid" to 7 * h))), plan.alerts.filterIsInstance<Alert.Summary>())
    }

    @Test
    fun afterTheParentsMidnightAChildStillOnItsDayIsJudgedOnItOnce() {
        // 00:30 in Tokyo, the child (UTC) is still on the 10th: its 10th is its today (final fix item 7). Already
        // notified for the 10th: nothing again; the child's yesterday is never read as its today.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = at(tokyo, 11, 0, 30)
        val total = rule(ThresholdTarget.Total, h)
        val snapshot = ready(summaries(now - h, "UTC", oct10 to 5 * h))
        val notified = AlertMarkers(initialized = true, thresholdNotified = setOf(AlertMarkers.thresholdKey(total.id, h, oct10)))
        val again = AlertPlanner.plan(AlertPlanner.input(snapshot, Prefs(), notified, now, tokyo, listOf(total)))
        assertTrue(again.alerts.none { it is Alert.Threshold }, "${again.alerts}")
        // Once the child's own midnight passed (UTC 00:30 on the 11th), its 10th is yesterday: nothing fires.
        val later = at(utc, 11, 0, 30)
        val next = AlertPlanner.plan(AlertPlanner.input(snapshot, Prefs(), AlertMarkers(initialized = true), later, tokyo, listOf(total)))
        assertTrue(next.alerts.none { it is Alert.Threshold }, "${next.alerts}")
    }

    @Test
    fun aChildNineHoursBehindCrossingItsLimitAfterTheParentsMidnightIsCaught() {
        // Final fix item 7: 00:30 on the 11th for the parent in Madrid (UTC+2) is 15:30 on the 10th for the child in
        // Los Angeles (UTC−7). The child passes its one-hour limit in its afternoon, after the parent's midnight.
        val madrid = ZoneId.of("Europe/Madrid")
        val now = at(madrid, 11, 0, 30)
        val total = rule(ThresholdTarget.Total, h)
        val snapshot = ready(summaries(now - 10 * min, "America/Los_Angeles", oct10 to 70 * min))
        val input = AlertPlanner.input(snapshot, Prefs(), AlertMarkers(initialized = true), now, madrid, listOf(total))
        assertEquals(oct10, input.todays.getValue(kidKeys.deviceId).date)
        val plan = AlertPlanner.plan(input)
        assertEquals(
            listOf(Alert.Threshold(kidKeys.deviceId, "Kid", ThresholdTarget.Total, null, 70 * min, h)),
            plan.alerts.filterIsInstance<Alert.Threshold>(),
        )
        assertEquals(setOf(AlertMarkers.thresholdKey(total.id, h, oct10)), plan.markers.thresholdNotified)
    }

    @Test
    fun overridesMoveAppsBetweenCategoryRules() {
        val now = at(utc, 10, 12)
        val snapshot = ready(summaries(now - h, "UTC", oct10 to 2 * h))
        val games = rule(ThresholdTarget.Category(AppCategory.GAMES), h)
        val video = rule(ThresholdTarget.Category(AppCategory.VIDEO), h)
        val overrides = mapOf(roblox to AppCategory.VIDEO)
        val plan = AlertPlanner.plan(AlertPlanner.input(snapshot, Prefs(), AlertMarkers(initialized = true), now, utc, listOf(games, video), overrides))
        assertEquals(listOf(ThresholdTarget.Category(AppCategory.VIDEO)), plan.alerts.filterIsInstance<Alert.Threshold>().map { it.target })
    }
}
