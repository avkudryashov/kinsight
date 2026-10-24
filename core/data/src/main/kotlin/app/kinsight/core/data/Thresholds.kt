package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.sync.AppRuleRules
import app.kinsight.core.sync.CategoryOverrideRules

/** What a daily limit counts (spec §9.7, stage 5b): the day total, one category, or one app. */
sealed interface ThresholdTarget {
    data object Total : ThresholdTarget

    data class Category(val category: AppCategory) : ThresholdTarget

    data class App(val packageName: String) : ThresholdTarget
}

/** What a rule does to its target (spec §4): a daily [LIMIT], a [BLOCK] of the target, or an [ALLOW] exception for one app. */
enum class RuleMode { LIMIT, BLOCK, ALLOW }

/**
 * A parent's rule for one child (spec §4, §9.7): a daily limit (optionally blocking at the limit), a block of an app or
 * category, or an allow for one app. One rule per (child, target): [id] is derived from those two alone ([idOf]), so a
 * changed rule keeps its id. Build rules with [of], [block] or [allow], which accept exactly the shapes the receiving
 * rules do (`AppRuleRules.shapeOk`); a limit is zero for BLOCK and ALLOW. [author] is the parent whose signed entry set
 * the rule (null for the phone-local limits of the old notify-only stage).
 */
data class ThresholdRule(
    val id: String,
    val child: DeviceId,
    val target: ThresholdTarget,
    val mode: RuleMode = RuleMode.LIMIT,
    val limitMs: Long = 0L,
    val blockOnLimit: Boolean = false,
    val author: DeviceId? = null,
) {
    /** Whether this rule is what [of], [block] or [allow] would build: the derived id and an accepted shape. */
    val isValid: Boolean get() = id == idOf(child, target) && shapeOk(target, mode, limitMs, blockOnLimit)

    companion object {
        const val STEP_MS = AppRuleRules.STEP_MS
        const val MIN_MS = AppRuleRules.MIN_MS
        const val MAX_MS = AppRuleRules.MAX_MS
        const val MAX_PACKAGE_LENGTH = 255

        /** Stable, persisted (alert markers carry it): `<child hex>/total`, `/cat:<CATEGORY>` or `/app:<package>`. */
        fun idOf(child: DeviceId, target: ThresholdTarget): String = "${child.hex}/${keyOf(target)}"

        /** Null for a limit off the 15-minute step or outside 15 min..12 h, or an app that is not a package. */
        fun of(child: DeviceId, target: ThresholdTarget, limitMs: Long, blockOnLimit: Boolean = false): ThresholdRule? =
            build(child, target, RuleMode.LIMIT, limitMs, blockOnLimit)

        /** Null for the day total (only limited) or an app that is not a package. */
        fun block(child: DeviceId, target: ThresholdTarget): ThresholdRule? = build(child, target, RuleMode.BLOCK, 0L, false)

        /** Null for a package that is not an app (an allow names one app only). */
        fun allow(child: DeviceId, packageName: String): ThresholdRule? =
            build(child, ThresholdTarget.App(packageName), RuleMode.ALLOW, 0L, false)

        private fun build(child: DeviceId, target: ThresholdTarget, mode: RuleMode, limitMs: Long, blockOnLimit: Boolean) =
            if (shapeOk(target, mode, limitMs, blockOnLimit)) ThresholdRule(idOf(child, target), child, target, mode, limitMs, blockOnLimit) else null

        /** The shared shape check: the wire fields of the rule go through `AppRuleRules.shapeOk`. */
        private fun shapeOk(target: ThresholdTarget, mode: RuleMode, limitMs: Long, blockOnLimit: Boolean): Boolean {
            val (t, pkg, cat) = when (target) {
                ThresholdTarget.Total -> Triple(AppRule.Target.TOTAL, "", 0)
                is ThresholdTarget.Category -> Triple(AppRule.Target.CATEGORY, "", CategoryOverrides.wire(target.category))
                is ThresholdTarget.App -> Triple(AppRule.Target.APP, target.packageName, 0)
            }
            return AppRuleRules.shapeOk(t, mode.toWire(), pkg, cat, limitMs, blockOnLimit)
        }

        internal fun keyOf(target: ThresholdTarget): String = when (target) {
            ThresholdTarget.Total -> "total"
            is ThresholdTarget.Category -> "cat:${target.category.name}"
            is ThresholdTarget.App -> "app:${target.packageName}"
        }

        /** The inverse of [keyOf]; null for anything else. */
        internal fun targetOf(key: String): ThresholdTarget? = when {
            key == "total" -> ThresholdTarget.Total
            key.startsWith("cat:") -> AppCategory.entries.firstOrNull { it.name == key.removePrefix("cat:") }?.let(ThresholdTarget::Category)
            key.startsWith("app:") -> ThresholdTarget.App(key.removePrefix("app:")).takeIf { CategoryOverrideRules.packageOk(it.packageName) }
            else -> null
        }
    }
}

/**
 * A child's today as the thresholds read it, built by [UsageReadModel.todayUsage] from the summary of the child's own
 * date: the day total, the stage 5a category stack with the viewer's overrides (summing to the total, `~other` and
 * unaccounted time in Other), and each app's time (`~other` included as its own key).
 */
data class TodayUsage(val totalMs: Long, val byCategory: Map<AppCategory, Long>, val byApp: Map<String, Long>)

object ThresholdEval {
    /** The time [target] counts in [today]; 0 for a category or app without time. */
    fun usage(target: ThresholdTarget, today: TodayUsage): Long = when (target) {
        ThresholdTarget.Total -> today.totalMs
        is ThresholdTarget.Category -> today.byCategory[target.category] ?: 0L
        is ThresholdTarget.App -> today.byApp[target.packageName] ?: 0L
    }

    /** A limit rule of [child] names this app or its [category]: the «лимит» mark on an app row (UI refresh §3, §4). */
    fun limited(rules: List<ThresholdRule>, child: DeviceId, packageName: String, category: AppCategory): Boolean =
        rules.any { it.child == child && it.mode == RuleMode.LIMIT && names(it, packageName, category) }

    /** The one mark an app row shows (spec §4): an allow beats a block, an app's block beats its category's, both beat a limit. */
    fun markOf(rules: List<ThresholdRule>, child: DeviceId, packageName: String, category: AppCategory): RuleMark {
        val mine = rules.filter { it.child == child && names(it, packageName, category) }
        return when {
            mine.any { it.mode == RuleMode.ALLOW } -> RuleMark.ALLOWED
            mine.any { it.mode == RuleMode.BLOCK } -> RuleMark.BLOCKED
            mine.any { it.mode == RuleMode.LIMIT } -> RuleMark.LIMIT
            else -> RuleMark.NONE
        }
    }

    private fun names(r: ThresholdRule, packageName: String, category: AppCategory) =
        r.target == ThresholdTarget.App(packageName) || r.target == ThresholdTarget.Category(category)
}

/** What an app row shows of the rules that reach it. */
enum class RuleMark { NONE, LIMIT, BLOCKED, ALLOWED }

internal fun RuleMode.toWire(): AppRule.Mode = when (this) {
    RuleMode.LIMIT -> AppRule.Mode.LIMIT
    RuleMode.BLOCK -> AppRule.Mode.BLOCK
    RuleMode.ALLOW -> AppRule.Mode.ALLOW
}
