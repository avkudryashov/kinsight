package app.kinsight.core.data

import kotlin.test.assertTrue
import kotlin.test.assertFalse
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThresholdsTest {
    private val kidA = DeviceId("aa".repeat(16))
    private val kidB = DeviceId("bb".repeat(16))
    private val min = 60_000L

    @Test
    fun limitIsFifteenMinuteStepsFromFifteenMinutesToTwelveHours() {
        assertEquals(15 * min, assertNotNull(ThresholdRule.of(kidA, ThresholdTarget.Total, 15 * min)).limitMs)
        assertNotNull(ThresholdRule.of(kidA, ThresholdTarget.Total, 12 * 60 * min))
        assertNotNull(ThresholdRule.of(kidA, ThresholdTarget.Total, 3 * 60 * min + 15 * min))
        for (bad in listOf(0L, -15 * min, 14 * min, 16 * min, 12 * 60 * min + 15 * min, 20 * min, 15 * min + 1, Long.MAX_VALUE)) {
            assertNull(ThresholdRule.of(kidA, ThresholdTarget.Total, bad), "limit $bad")
        }
    }

    @Test
    fun appTargetMustBeARealPackage() {
        assertNotNull(ThresholdRule.of(kidA, ThresholdTarget.App("com.example.game"), 60 * min))
        // ~other is the summary's remainder bucket, not an app.
        for (bad in listOf("", Categories.REMAINDER_PACKAGE, "a|b", "a b", "a\nb", "x".repeat(256))) {
            assertNull(ThresholdRule.of(kidA, ThresholdTarget.App(bad), 60 * min), "package '$bad'")
        }
    }

    @Test
    fun idIsDerivedFromChildAndTargetOnly() {
        val a = ThresholdRule.of(kidA, ThresholdTarget.Category(AppCategory.GAMES), 60 * min)!!
        val again = ThresholdRule.of(kidA, ThresholdTarget.Category(AppCategory.GAMES), 120 * min)!!
        assertEquals(a.id, again.id)
        assertEquals(ThresholdRule.idOf(kidA, ThresholdTarget.Category(AppCategory.GAMES)), a.id)
        val ids = listOf(
            ThresholdRule.idOf(kidA, ThresholdTarget.Total),
            ThresholdRule.idOf(kidB, ThresholdTarget.Total),
            ThresholdRule.idOf(kidA, ThresholdTarget.Category(AppCategory.GAMES)),
            ThresholdRule.idOf(kidA, ThresholdTarget.Category(AppCategory.VIDEO)),
            ThresholdRule.idOf(kidA, ThresholdTarget.App("com.example.game")),
            ThresholdRule.idOf(kidA, ThresholdTarget.App("GAMES")),
        )
        assertEquals(ids.size, ids.toSet().size)
        // Pinned: markers persist ids, so the format must not drift.
        assertEquals("${"aa".repeat(16)}/total", ThresholdRule.idOf(kidA, ThresholdTarget.Total))
        assertEquals("${"aa".repeat(16)}/cat:GAMES", ThresholdRule.idOf(kidA, ThresholdTarget.Category(AppCategory.GAMES)))
        assertEquals("${"aa".repeat(16)}/app:com.x", ThresholdRule.idOf(kidA, ThresholdTarget.App("com.x")))
        assertNotEquals(a, again)
    }

    @Test
    fun usagePerTarget() {
        val today = TodayUsage(
            totalMs = 100 * min,
            byCategory = mapOf(AppCategory.GAMES to 60 * min, AppCategory.OTHER to 40 * min),
            byApp = mapOf("com.roblox.client" to 60 * min, Categories.REMAINDER_PACKAGE to 30 * min),
        )
        assertEquals(100 * min, ThresholdEval.usage(ThresholdTarget.Total, today))
        assertEquals(60 * min, ThresholdEval.usage(ThresholdTarget.Category(AppCategory.GAMES), today))
        assertEquals(0L, ThresholdEval.usage(ThresholdTarget.Category(AppCategory.VIDEO), today))
        assertEquals(60 * min, ThresholdEval.usage(ThresholdTarget.App("com.roblox.client"), today))
        assertEquals(0L, ThresholdEval.usage(ThresholdTarget.App("com.unused"), today))
    }

    // The builder over signed summaries (UsageReadModel.todayUsage).
    private val kidKeys = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(DeviceKeys.generate(), "Mom").groupId
    private val kid = EntryAuthor(groupId, kidKeys, lastSeq = 0)
    private val child = MemberView(kidKeys.deviceId, "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val date = LocalDate.of(2026, 10, 10)

    private fun summary(day: LocalDate, totalMin: Long, vararg apps: Pair<String, Long>, zone: String = "UTC") = kid.create {
        it.copy(
            usage_summary = UsageSummary(
                local_date = day.toString(), zone_id = zone, total_ms = totalMin * min,
                apps = apps.map { (p, m) -> AppTotal(p, m * min) },
            ),
        )
    }

    private fun byDate(vararg e: app.kinsight.core.model.DecodedEntry) = UsageReadModel.summaries(e.toList())[kidKeys.deviceId]

    @Test
    fun todayUsageMapsCategoriesWithOverridesAndRemainder() {
        val by = byDate(
            summary(date, 100, "com.roblox.client" to 50L, "com.example.reader" to 20L, Categories.REMAINDER_PACKAGE to 15L),
            summary(date.minusDays(1), 500, "com.roblox.client" to 500L),
        )
        val catalog = mapOf("com.example.reader" to AppCatalog(package_name = "com.example.reader", label = "Reader", category = 5))
        val noon = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        val plain = assertNotNull(UsageReadModel.todayUsage(child, by, catalog, date, emptyMap(), noon))
        assertEquals(100 * min, plain.totalMs)
        // ~other and the 15 minutes no app accounts for go to Other.
        assertEquals(mapOf(AppCategory.GAMES to 50 * min, AppCategory.NEWS to 20 * min, AppCategory.OTHER to 30 * min), plain.byCategory)
        assertEquals(mapOf("com.roblox.client" to 50 * min, "com.example.reader" to 20 * min, Categories.REMAINDER_PACKAGE to 15 * min), plain.byApp)

        // The viewer's override moves the app; an override on ~other does not move the remainder.
        val overrides = mapOf("com.roblox.client" to AppCategory.PRODUCTIVITY, Categories.REMAINDER_PACKAGE to AppCategory.GAMES)
        val moved = assertNotNull(UsageReadModel.todayUsage(child, by, catalog, date, overrides, noon))
        assertEquals(mapOf(AppCategory.PRODUCTIVITY to 50 * min, AppCategory.NEWS to 20 * min, AppCategory.OTHER to 30 * min), moved.byCategory)
        assertEquals(0L, ThresholdEval.usage(ThresholdTarget.Category(AppCategory.GAMES), moved))
        assertEquals(30 * min, ThresholdEval.usage(ThresholdTarget.Category(AppCategory.OTHER), moved))
    }

    @Test
    fun todayUsageIsTheChildsToday() {
        // The child is in a zone already on the 11th: its today is the 11th, not the parent's 10th.
        val by = byDate(summary(date, 300, zone = "Pacific/Kiritimati"), summary(date.plusDays(1), 20, zone = "Pacific/Kiritimati"))
        val lateEvening = date.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(20 * min, assertNotNull(UsageReadModel.todayUsage(child, by, emptyMap(), date, emptyMap(), lateEvening)).totalMs)
        // No summary for the child's today yet: nothing to evaluate (never yesterday's total).
        val only = byDate(summary(date, 300, zone = "Pacific/Kiritimati"))
        assertNull(UsageReadModel.todayUsage(child, only, emptyMap(), date, emptyMap(), lateEvening))
    }

    @Test
    fun todayUsageOfAChildBehindTheParentIsItsActualDate() {
        // Final fix item 7: 07:30 UTC on the 11th for the parent is 22:30 on the 10th for a child 9 h behind (UTC−9).
        val by = byDate(summary(date, 200, zone = "Pacific/Gambier"))
        val parentMorning = date.plusDays(1).atTime(7, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(date, UsageReadModel.childToday(by, date.plusDays(1), parentMorning, thresholds = true))
        assertEquals(200 * min, assertNotNull(UsageReadModel.todayUsage(child, by, emptyMap(), date.plusDays(1), emptyMap(), parentMorning)).totalMs)
        // The screens keep the later of the two dates: the detail and the dashboard open on the parent's today.
        assertEquals(date.plusDays(1), UsageReadModel.childToday(by, date.plusDays(1), parentMorning))
    }

    @Test
    fun todayUsageOnlyForChildren() {
        val by = byDate(summary(date, 100))
        val noon = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertNull(UsageReadModel.todayUsage(child.copy(role = Role.PARENT), by, emptyMap(), date, emptyMap(), noon))
        assertNull(UsageReadModel.todayUsage(child, null, emptyMap(), date, emptyMap(), noon))
    }

    @Test
    fun anAppIsLimitedByItsOwnRuleOrItsCategorysForThatChildOnly() {
        val rules = listOfNotNull(
            ThresholdRule.of(kidA, ThresholdTarget.App("com.example.game"), 60 * min),
            ThresholdRule.of(kidA, ThresholdTarget.Category(AppCategory.VIDEO), 60 * min),
            ThresholdRule.of(kidA, ThresholdTarget.Total, 120 * min),
        )
        assertTrue(ThresholdEval.limited(rules, kidA, "com.example.game", AppCategory.GAMES))
        assertTrue(ThresholdEval.limited(rules, kidA, "com.example.tube", AppCategory.VIDEO))
        assertFalse(ThresholdEval.limited(rules, kidA, "com.example.chat", AppCategory.SOCIAL), "the day total alone is no pill")
        assertFalse(ThresholdEval.limited(rules, kidB, "com.example.game", AppCategory.GAMES))
    }

    @Test
    fun blockHasNoLimitAndNeverTotal() {
        assertNull(ThresholdRule.block(kidA, ThresholdTarget.Total))
        assertEquals(0L, ThresholdRule.block(kidA, ThresholdTarget.App("com.x"))!!.limitMs)
        assertNull(ThresholdRule.allow(kidA, Categories.REMAINDER_PACKAGE))
        assertNull(ThresholdRule.allow(kidA, "~other"))
        assertEquals(RuleMode.ALLOW, ThresholdRule.allow(kidA, "com.x")!!.mode)
    }

    @Test
    fun markPrefersAllowThenBlockThenLimit() {
        val rules = listOfNotNull(
            ThresholdRule.allow(kidA, "com.x"),
            ThresholdRule.block(kidA, ThresholdTarget.Category(AppCategory.GAMES)),
            ThresholdRule.of(kidA, ThresholdTarget.App("com.y"), 60 * min),
        )
        assertEquals(RuleMark.ALLOWED, ThresholdEval.markOf(rules, kidA, "com.x", AppCategory.GAMES))
        assertEquals(RuleMark.BLOCKED, ThresholdEval.markOf(rules, kidA, "com.z", AppCategory.GAMES))
        assertEquals(RuleMark.LIMIT, ThresholdEval.markOf(rules, kidA, "com.y", AppCategory.VIDEO))
        assertEquals(RuleMark.NONE, ThresholdEval.markOf(rules, kidA, "com.w", AppCategory.VIDEO))
        assertEquals(RuleMark.NONE, ThresholdEval.markOf(rules, kidB, "com.x", AppCategory.GAMES))
    }

    @Test
    fun blockOnLimitOnlyForLimits() {
        assertTrue(ThresholdRule.of(kidA, ThresholdTarget.Total, 60 * min, blockOnLimit = true)!!.blockOnLimit)
        assertFalse(ThresholdEval.limited(listOfNotNull(ThresholdRule.block(kidA, ThresholdTarget.App("com.x"))), kidA, "com.x", AppCategory.GAMES))
    }
}
