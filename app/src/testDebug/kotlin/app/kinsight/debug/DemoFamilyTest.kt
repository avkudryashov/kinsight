package app.kinsight.debug

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.RuleMode
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DEMO_FAMILY_CHILD's extras parsing only; the signing and storing are exercised on an emulator (README-emulator.md). */
class DemoFamilyTest {
    private val child = DeviceId("00112233445566778899aabbccddeeff")

    @Test
    fun noExtrasAreTheDefaults() {
        val spec = DemoRuleSpec.parse(null, null, null, null, null)
        assertEquals(DemoRuleSpec.DEFAULT, spec)
        val rules = spec.rules(child)
        assertEquals(3, rules.size)
        assertTrue(rules.all { it.isValid })
        assertEquals(RuleMode.BLOCK, rules.single { it.target == ThresholdTarget.App("com.android.chrome") }.mode)
        assertEquals(RuleMode.ALLOW, rules.single { it.target == ThresholdTarget.App("com.google.android.dialer") }.mode)
        val total = rules.single { it.target == ThresholdTarget.Total }
        assertEquals(15 * 60_000L, total.limitMs)
        assertTrue(total.blockOnLimit)
    }

    @Test
    fun everyExtraBecomesItsRule() {
        val spec = DemoRuleSpec.parse(" com.a, com.b ,,com.a", "games,SOCIAL", "com.c", 0, "com.d=30, com.e=45")
        assertEquals(listOf("com.a", "com.b"), spec.block)
        assertEquals(listOf(AppCategory.GAMES, AppCategory.SOCIAL), spec.blockCategories)
        assertEquals(listOf("com.c"), spec.allow)
        assertEquals(0, spec.totalMin)
        assertEquals(mapOf("com.d" to 30, "com.e" to 45), spec.limits)
        val rules = spec.rules(child)
        assertEquals(7, rules.size)
        assertTrue(rules.all { it.isValid })
        assertTrue(rules.none { it.target == ThresholdTarget.Total })
        assertEquals(45 * 60_000L, rules.single { it.target == ThresholdTarget.App("com.e") }.limitMs)
    }

    @Test
    fun anyExtraReplacesTheDefaults() {
        val spec = DemoRuleSpec.parse("com.android.chrome", null, null, 2, null)
        assertEquals(DemoRuleSpec(block = listOf("com.android.chrome"), totalMin = 2), spec)
        assertEquals(15 * 60_000L, spec.rules(child).single { it.target == ThresholdTarget.Total }.limitMs)
    }

    @Test
    fun minutesGoUpToTheQuarterHourWithinTwelveHours() {
        assertEquals(15 * 60_000L, DemoRuleSpec.limitMs(1))
        assertEquals(15 * 60_000L, DemoRuleSpec.limitMs(15))
        assertEquals(30 * 60_000L, DemoRuleSpec.limitMs(16))
        assertEquals(12 * 60 * 60_000L, DemoRuleSpec.limitMs(10_000))
    }

    @Test
    fun badExtrasAreErrors() {
        assertThrows(IllegalArgumentException::class.java) { DemoRuleSpec.parse(null, "CARTOONS", null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { DemoRuleSpec.parse(null, null, null, null, "com.d") }
        assertThrows(IllegalArgumentException::class.java) { DemoRuleSpec.parse(null, null, null, null, "com.d=0") }
        assertThrows(IllegalArgumentException::class.java) { DemoRuleSpec.parse(null, null, null, -1, null) }
        assertThrows(IllegalStateException::class.java) { DemoRuleSpec.parse("not a package", null, null, null, null).rules(child) }
    }
}
