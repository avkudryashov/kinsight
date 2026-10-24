package app.kinsight.core.blocking

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppRules
import app.kinsight.core.data.BlockDecision
import app.kinsight.core.data.BlockReason
import app.kinsight.core.data.Categories
import app.kinsight.core.data.CategoryOverrides
import app.kinsight.core.data.GroupSnapshot
import app.kinsight.core.data.RuleMode
import app.kinsight.core.data.SystemAllowList
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.TodayUsage
import app.kinsight.core.data.UsageReadModel
import app.kinsight.core.usage.UsageSettings

/**
 * The pure part of the enforcement service (app blocking spec §5.2): what to do when a window comes up, and on the
 * minute tick for the app still on screen (a limit can run out while it is open). Runs [BlockDecision] on the inputs
 * the service caches; never blocks itself. Not thread-safe: the service calls it on the main thread only.
 *
 * [current] is the app the tick re-checks. Kinsight's own windows ([ownPackage]) and a launcher ([isHome]) clear it: the
 * app is no longer in front. Other allow-listed windows (keyboard, notification shade, volume panel) come up over the
 * app and leave it as is, or one pull of the shade would end its limit. One Block per package within [DEBOUNCE_MS]: the
 * burst of window events an app raises while it opens closes it once, and a late event of the closed app does not make
 * it [current] again.
 */
class Enforcer(
    private val rules: () -> List<ThresholdRule>,
    private val categoryOf: (String) -> AppCategory,
    private val today: () -> TodayUsage?,
    private val pinAllowed: () -> Set<String>,
    private val system: SystemAllowList,
    private val isHome: (String) -> Boolean,
    private val ownPackage: String,
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
) {
    sealed interface Action {
        data object None : Action

        data class Block(val packageName: String, val reason: BlockReason) : Action
    }

    /** The app on screen; null after a Block (it is closed), in Kinsight or on the launcher. */
    var current: String? = null
        private set
    private var lastBlock: String? = null
    private var lastBlockAtMs = 0L

    fun onForeground(packageName: String): Action {
        if (packageName == ownPackage || isHome(packageName)) {
            current = null
            return Action.None
        }
        if (system.contains(packageName)) return Action.None
        if (debounced(packageName)) return Action.None
        current = packageName
        return decide(packageName)
    }

    fun onTick(): Action = current?.let(::decide) ?: Action.None

    private fun debounced(packageName: String) = packageName == lastBlock && clock() - lastBlockAtMs in 0 until DEBOUNCE_MS

    private fun decide(packageName: String): Action {
        if (!enabled()) return Action.None
        val verdict = BlockDecision.decide(packageName, categoryOf(packageName), rules(), today(), pinAllowed(), system)
        val reason = verdict.reason ?: return Action.None
        if (debounced(packageName)) return Action.None
        lastBlock = packageName
        lastBlockAtMs = clock()
        current = null
        return Action.Block(packageName, reason)
    }

    companion object {
        const val DEBOUNCE_MS = 1_000L
        const val TICK_MS = 60_000L
        const val USAGE_REFRESH_MS = 30_000L
    }
}

/**
 * What the service caches from the snapshot and the consent (spec §5.2): this child's own effective rules, its effective
 * category per package (family overrides over its own catalog, the same function for [TodayUsageReader]'s stack), and
 * whether to enforce at all: a member child with an up-to-date consent and at least one blocking rule. [needsUsage]:
 * a LIMIT with `blockOnLimit` is among them, so today's usage is worth reading (a BLOCK-only set never needs it).
 */
class EnforcerInputs(val enabled: Boolean, val rules: List<ThresholdRule>, val categoryOf: (String) -> AppCategory) {
    val needsUsage: Boolean = enabled && rules.any { it.mode == RuleMode.LIMIT && it.blockOnLimit }

    companion object {
        val OFF = EnforcerInputs(false, emptyList()) { AppCategory.OTHER }

        fun of(snapshot: GroupSnapshot, settings: UsageSettings): EnforcerInputs {
            val ready = (snapshot as? GroupSnapshot.Ready)?.takeUnless { it.removed } ?: return OFF
            if (ready.membership.active[ready.self]?.isChild != true || settings.consentOutdated) return OFF
            val rules = AppRules.effective(ready).filter { it.child == ready.self }
            if (!BlockDecision.enforcing(rules)) return OFF
            val overrides = CategoryOverrides.effective(ready)
            val catalog = UsageReadModel.catalogOf(ready.catalog, ready.self)
            return EnforcerInputs(true, rules) { Categories.of(it, catalog[it]?.category, overrides) }
        }
    }
}
