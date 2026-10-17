package app.kinsight.core.data

import app.kinsight.core.model.DeviceId

/** What a daily limit counts (spec §9.7, stage 5b): the day total, one category, or one app. */
sealed interface ThresholdTarget {
    data object Total : ThresholdTarget

    data class Category(val category: AppCategory) : ThresholdTarget

    data class App(val packageName: String) : ThresholdTarget
}

/**
 * A parent's daily limit for one child (spec §9.7, stage 5b): local to the parent's phone, never signed or synced;
 * it only notifies, nothing is blocked (§1). One rule per (child, target): [id] is derived from those two alone
 * ([idOf]), so a changed limit keeps its id. Build rules with [of], which refuses an invalid limit or target.
 */
data class ThresholdRule(val id: String, val child: DeviceId, val target: ThresholdTarget, val limitMs: Long) {
    /** Whether this rule is what [of] would build: the derived id, a valid target and an on-step limit in range. */
    val isValid: Boolean get() = id == idOf(child, target) && limitOk(limitMs) && targetOk(target)

    companion object {
        const val STEP_MS = 15 * 60_000L
        const val MIN_MS = STEP_MS
        const val MAX_MS = 12 * 3_600_000L
        const val MAX_PACKAGE_LENGTH = 255

        /** Stable, persisted (alert markers carry it): `<child hex>/total`, `/cat:<CATEGORY>` or `/app:<package>`. */
        fun idOf(child: DeviceId, target: ThresholdTarget): String = "${child.hex}/${keyOf(target)}"

        /** Null for a limit off the 15-minute step or outside 15 min..12 h, or an app that is not a package. */
        fun of(child: DeviceId, target: ThresholdTarget, limitMs: Long): ThresholdRule? =
            if (limitOk(limitMs) && targetOk(target)) ThresholdRule(idOf(child, target), child, target, limitMs) else null

        internal fun keyOf(target: ThresholdTarget): String = when (target) {
            ThresholdTarget.Total -> "total"
            is ThresholdTarget.Category -> "cat:${target.category.name}"
            is ThresholdTarget.App -> "app:${target.packageName}"
        }

        /** The inverse of [keyOf]; null for anything else. */
        internal fun targetOf(key: String): ThresholdTarget? = when {
            key == "total" -> ThresholdTarget.Total
            key.startsWith("cat:") -> AppCategory.entries.firstOrNull { it.name == key.removePrefix("cat:") }?.let(ThresholdTarget::Category)
            key.startsWith("app:") -> ThresholdTarget.App(key.removePrefix("app:")).takeIf(::targetOk)
            else -> null
        }

        private fun limitOk(ms: Long) = ms in MIN_MS..MAX_MS && ms % STEP_MS == 0L

        /** The `~other` remainder is not an app; a package never holds the storage separators or whitespace. */
        private fun targetOk(t: ThresholdTarget) = t !is ThresholdTarget.App || t.packageName.let { p ->
            p.isNotEmpty() && p.length <= MAX_PACKAGE_LENGTH && p != Categories.REMAINDER_PACKAGE && p.none { it == '|' || it == '/' || it.isWhitespace() }
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

    /** A rule of [child] names this app or its [category]: the «лимит» mark on an app row (UI refresh §3, §4). */
    fun limited(rules: List<ThresholdRule>, child: DeviceId, packageName: String, category: AppCategory): Boolean =
        rules.any { it.child == child && (it.target == ThresholdTarget.App(packageName) || it.target == ThresholdTarget.Category(category)) }
}
