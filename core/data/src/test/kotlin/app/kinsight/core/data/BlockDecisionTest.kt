package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockDecisionTest {
    private val child = DeviceId("cc".repeat(16))
    private val pkg = "com.game.app"
    private val cat = AppCategory.GAMES
    private val h = 60 * 60 * 1000L
    private val noSystem = SystemAllowList { false }

    private fun block(t: ThresholdTarget) = assertNotNull(ThresholdRule.block(child, t))
    private fun allow() = assertNotNull(ThresholdRule.allow(child, pkg))
    private fun limit(t: ThresholdTarget, ms: Long = h, blockOnLimit: Boolean = true) = assertNotNull(ThresholdRule.of(child, t, ms, blockOnLimit))
    private fun usage(total: Long = 0, c: Long = 0, a: Long = 0) = TodayUsage(total, mapOf(cat to c), mapOf(pkg to a))

    private fun decide(
        rules: List<ThresholdRule>,
        today: TodayUsage? = usage(),
        pin: Set<String> = emptySet(),
        system: SystemAllowList = noSystem,
    ) = BlockDecision.decide(pkg, cat, rules, today, pin, system)

    @Test
    fun systemListWins() {
        val v = decide(listOf(block(ThresholdTarget.App(pkg))), system = SystemAllowList { it == pkg })
        assertEquals(BlockVerdict(false), v)
    }

    @Test
    fun allowBeatsEveryBlock() {
        val over = usage(total = 5 * h, c = 5 * h, a = 5 * h)
        val rules = listOf(
            allow(),
            block(ThresholdTarget.App("other.pkg")),
            block(ThresholdTarget.Category(cat)),
            limit(ThresholdTarget.App(pkg)),
            limit(ThresholdTarget.Category(cat)),
            limit(ThresholdTarget.Total),
        )
        assertEquals(BlockVerdict(false), decide(rules, over))
    }

    @Test
    fun pinOverrideOpensABlockedApp() {
        val rules = listOf(block(ThresholdTarget.App(pkg)), block(ThresholdTarget.Category(cat)), limit(ThresholdTarget.Total))
        val over = usage(total = 5 * h)
        assertEquals(BlockVerdict(true, BlockReason.Blocked), decide(rules.take(1), over))
        assertEquals(BlockVerdict(false), decide(rules, over, pin = setOf(pkg)))
        assertEquals(BlockVerdict(true, BlockReason.Blocked), decide(rules.take(1), over, pin = setOf("another.pkg")), "a PIN opens one package only")
    }

    @Test
    fun appBlockBeforeCategory() {
        val rules = listOf(block(ThresholdTarget.Category(cat)), block(ThresholdTarget.App(pkg)))
        assertEquals(BlockVerdict(true, BlockReason.Blocked), decide(rules))
    }

    @Test
    fun categoryBlock() {
        assertEquals(BlockVerdict(true, BlockReason.CategoryBlocked(cat)), decide(listOf(block(ThresholdTarget.Category(cat)))))
        assertEquals(BlockVerdict(false), decide(listOf(block(ThresholdTarget.Category(AppCategory.VIDEO)))), "another category does not reach the app")
    }

    @Test
    fun limitPassedAppThenCategoryThenTotal() {
        val app = ThresholdTarget.App(pkg)
        val category = ThresholdTarget.Category(cat)
        val all = listOf(limit(ThresholdTarget.Total, 3 * h), limit(category, 2 * h), limit(app, h))
        val over = usage(total = 4 * h, c = 3 * h, a = 2 * h)
        assertEquals(BlockVerdict(true, BlockReason.LimitPassed(app, h)), decide(all, over))
        assertEquals(BlockVerdict(true, BlockReason.LimitPassed(category, 2 * h)), decide(all, usage(total = 4 * h, c = 3 * h, a = h / 2)))
        assertEquals(BlockVerdict(true, BlockReason.LimitPassed(ThresholdTarget.Total, 3 * h)), decide(all, usage(total = 4 * h, c = h, a = h / 2)))
        assertEquals(BlockVerdict(false), decide(all, usage(total = 3 * h - 1, c = 2 * h - 1, a = h - 1)), "below every limit")
        assertEquals(BlockVerdict(true, BlockReason.LimitPassed(app, h)), decide(all, usage(a = h)), "usage equal to the limit has passed it")
    }

    @Test
    fun limitWithoutBlockOnLimitNeverBlocks() {
        val rules = listOf(
            limit(ThresholdTarget.App(pkg), blockOnLimit = false),
            limit(ThresholdTarget.Category(cat), blockOnLimit = false),
            limit(ThresholdTarget.Total, blockOnLimit = false),
        )
        assertEquals(BlockVerdict(false), decide(rules, usage(total = 9 * h, c = 9 * h, a = 9 * h)))
    }

    @Test
    fun nothingWithoutTodayUsage() {
        val limits = listOf(limit(ThresholdTarget.App(pkg)), limit(ThresholdTarget.Category(cat)), limit(ThresholdTarget.Total))
        assertEquals(BlockVerdict(false), decide(limits, today = null))
        assertEquals(BlockVerdict(true, BlockReason.Blocked), decide(limits + block(ThresholdTarget.App(pkg)), today = null))
        assertEquals(BlockVerdict(true, BlockReason.CategoryBlocked(cat)), decide(limits + block(ThresholdTarget.Category(cat)), today = null))
    }

    @Test
    fun systemListIsReadPerDecision() {
        val set = mutableSetOf(pkg)
        val system = SystemAllowList { it in set }
        val rules = listOf(block(ThresholdTarget.App(pkg)))
        assertEquals(BlockVerdict(false), decide(rules, system = system))
        set.clear()
        assertEquals(BlockVerdict(true, BlockReason.Blocked), decide(rules, system = system))
    }

    @Test
    fun enforcingIsTrueOnlyForBlockingRules() {
        assertFalse(BlockDecision.enforcing(emptyList()))
        assertFalse(BlockDecision.enforcing(listOf(allow(), limit(ThresholdTarget.Total, blockOnLimit = false))))
        assertTrue(BlockDecision.enforcing(listOf(block(ThresholdTarget.App(pkg)))))
        assertTrue(BlockDecision.enforcing(listOf(block(ThresholdTarget.Category(cat)))))
        assertTrue(BlockDecision.enforcing(listOf(allow(), limit(ThresholdTarget.Total, blockOnLimit = true))))
    }
}
