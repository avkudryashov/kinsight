package app.kinsight.core.data

import app.kinsight.core.usage.UsageSettings

/** Why an app is blocked (spec §5.1 steps 4-6). */
sealed interface BlockReason {
    /** A parent's BLOCK rule on the app itself. */
    data object Blocked : BlockReason

    /** A parent's BLOCK rule on the app's [category]. */
    data class CategoryBlocked(val category: AppCategory) : BlockReason

    /** A LIMIT with `blockOnLimit` on [target] whose usage reached [limitMs]. */
    data class LimitPassed(val target: ThresholdTarget, val limitMs: Long) : BlockReason
}

/** The decision for one app: [reason] is set exactly when [blocked]. */
data class BlockVerdict(val blocked: Boolean, val reason: BlockReason? = null)

/** Packages that are never blocked (launcher, dialer, settings, the app itself; spec §5.1 step 1). Read on every decision. */
fun interface SystemAllowList {
    fun contains(packageName: String): Boolean
}

/**
 * The pure «is this app blocked now» decision of the child's enforcement service (spec §5.1). Precedence, the first match decides:
 * 1 system allow list, 2 parent's ALLOW on the package, 3 PIN override for the package today, 4 BLOCK on the package,
 * 5 BLOCK on its category, 6 LIMIT with `blockOnLimit` and usage at or over the limit (app, then category, then Total), 7 allowed.
 */
object BlockDecision {
    /**
     * [rules] are this child's effective rules; `decide` does not filter by `ThresholdRule.child`. [pinAllowed] are the packages
     * opened by PIN for today (the caller derives them from `AppPrefs.pinOverrides`). A null [today] means no usage is known,
     * so limits never block (explicit BLOCK rules still do).
     */
    fun decide(
        packageName: String,
        category: AppCategory,
        rules: List<ThresholdRule>,
        today: TodayUsage?,
        pinAllowed: Set<String>,
        system: SystemAllowList,
    ): BlockVerdict {
        if (system.contains(packageName)) return ALLOWED
        val app = ThresholdTarget.App(packageName)
        val cat = ThresholdTarget.Category(category)
        if (rules.any { it.mode == RuleMode.ALLOW && it.target == app }) return ALLOWED
        if (packageName in pinAllowed) return ALLOWED
        if (rules.any { it.mode == RuleMode.BLOCK && it.target == app }) return BlockVerdict(true, BlockReason.Blocked)
        if (rules.any { it.mode == RuleMode.BLOCK && it.target == cat }) return BlockVerdict(true, BlockReason.CategoryBlocked(category))
        if (today != null) {
            for (target in listOf(app, cat, ThresholdTarget.Total)) {
                val passed = rules.firstOrNull {
                    it.mode == RuleMode.LIMIT && it.blockOnLimit && it.target == target && ThresholdEval.usage(target, today) >= it.limitMs
                }
                if (passed != null) return BlockVerdict(true, BlockReason.LimitPassed(target, passed.limitMs))
            }
        }
        return ALLOWED
    }

    /** Whether the enforcement service has anything to do: a BLOCK rule or a LIMIT with `blockOnLimit`. */
    fun enforcing(rules: List<ThresholdRule>): Boolean =
        rules.any { it.mode == RuleMode.BLOCK || (it.mode == RuleMode.LIMIT && it.blockOnLimit) }

    /**
     * This phone's own effective rules while it enforces them (spec §5.2, §8): a member child of a Ready group, not
     * removed, whose consent names blocking ([UsageSettings.blockingConsented]), with at least one [enforcing] rule; empty
     * otherwise. The one gate of the service's inputs, the setup's accessibility step and the BLOCKING_OFF watch.
     */
    fun enforcedRules(snapshot: GroupSnapshot, settings: UsageSettings): List<ThresholdRule> {
        val ready = (snapshot as? GroupSnapshot.Ready)?.takeUnless { it.removed } ?: return emptyList()
        if (ready.membership.active[ready.self]?.isChild != true || !settings.blockingConsented) return emptyList()
        return AppRules.effective(ready).filter { it.child == ready.self }.takeIf(::enforcing).orEmpty()
    }

    private val ALLOWED = BlockVerdict(false)
}
