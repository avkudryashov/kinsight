package app.kinsight.core.blocking

import app.kinsight.core.data.AppCategory
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
import java.time.LocalDate

/**
 * The pure part of the enforcement service (app blocking spec §5.2): what to do when a window comes up, and on the
 * minute tick for the app still on screen (a limit can run out while it is open). Runs [BlockDecision] on the inputs
 * the service caches; never blocks itself. Not thread-safe: the service calls it on the main thread only.
 *
 * [current] is the app the tick re-checks. Overlays ([isOverlay]: keyboard, notification shade, volume panel) come up
 * over the app and leave it as is, or one pull of the shade would end its limit. Every other allow-listed window is a
 * full app in front (Kinsight itself, a launcher, the dialer during a call, Settings) and clears it: nothing behind a
 * call is closed by the tick. One Block per package within [DEBOUNCE_MS]: the
 * burst of window events an app raises while it opens closes it once, and a late event of the closed app does not make
 * it [current] again. While [inCall] nothing is closed at all.
 *
 * [today] is cached by the service and dated: a value of another day than [date] (read before midnight) counts as
 * unknown, so a day total passed yesterday never blocks this morning's first app, and [usageStale] tells the service to
 * read usage again whether or not an app is [current] (a Block clears it, so `current` alone would never re-arm the read).
 */
class Enforcer(
    private val rules: () -> List<ThresholdRule>,
    private val categoryOf: (String) -> AppCategory,
    private val today: () -> DatedUsage?,
    private val date: () -> LocalDate,
    private val pinAllowed: () -> Set<String>,
    private val system: SystemAllowList,
    private val isHome: (String) -> Boolean,
    private val isOverlay: (String) -> Boolean,
    private val ownPackage: String,
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
    private val inCall: () -> Boolean = { false },
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

    /**
     * A window of the app just blocked, inside [DEBOUNCE_MS]: either a late event of the closed app (the block screen's
     * own window follows and clears this) or the app opening something new over the block screen (nothing follows).
     * [onDebounceElapsed] tells them apart: any later [onForeground] clears it.
     */
    private var pendingRecheck: String? = null

    val hasPendingRecheck: Boolean get() = pendingRecheck != null

    fun onForeground(packageName: String): Action {
        pendingRecheck = null
        if (packageName == ownPackage || isHome(packageName)) {
            current = null
            return Action.None
        }
        if (isOverlay(packageName)) return Action.None
        if (system.contains(packageName)) {
            current = null
            return Action.None
        }
        if (debounced(packageName)) {
            pendingRecheck = packageName
            return Action.None
        }
        current = packageName
        return decide(packageName)
    }

    /** [DEBOUNCE_MS] after a debounced window with no newer window since: the app is in front again, decide it now. */
    fun onDebounceElapsed(): Action {
        val pkg = pendingRecheck ?: return Action.None
        pendingRecheck = null
        current = pkg
        return decide(pkg)
    }

    fun onTick(): Action = current?.let(::decide) ?: Action.None

    /** The cached usage is of another day than today: read it again before trusting it (it counts as unknown meanwhile). */
    fun usageStale(): Boolean = today()?.let { it.date != date() } ?: false

    private fun todayUsage(): TodayUsage? = today()?.takeIf { it.date == date() }?.usage

    private fun debounced(packageName: String) = packageName == lastBlock && clock() - lastBlockAtMs in 0 until DEBOUNCE_MS

    private fun decide(packageName: String): Action {
        // A phone call is on: nothing closes (an in-call screen of an unknown package, a map during the call), but the
        // app stays current, so the tick re-checks it once the call is over.
        if (!enabled() || inCall()) return Action.None
        val verdict = BlockDecision.decide(packageName, categoryOf(packageName), rules(), todayUsage(), pinAllowed(), system)
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
 * whether to enforce at all ([BlockDecision.enforcedRules]: a member child whose consent names blocking, with at least
 * one blocking rule). [needsUsage]:
 * a LIMIT with `blockOnLimit` is among them, so today's usage is worth reading (a BLOCK-only set never needs it).
 */
class EnforcerInputs(val enabled: Boolean, val rules: List<ThresholdRule>, val categoryOf: (String) -> AppCategory) {
    val needsUsage: Boolean = enabled && rules.any { it.mode == RuleMode.LIMIT && it.blockOnLimit }

    companion object {
        val OFF = EnforcerInputs(false, emptyList()) { AppCategory.OTHER }

        fun of(snapshot: GroupSnapshot, settings: UsageSettings): EnforcerInputs {
            val rules = BlockDecision.enforcedRules(snapshot, settings)
            if (rules.isEmpty()) return OFF
            val ready = snapshot as GroupSnapshot.Ready
            val overrides = CategoryOverrides.effective(ready)
            val catalog = UsageReadModel.catalogOf(ready.catalog, ready.self)
            return EnforcerInputs(true, rules) { Categories.of(it, catalog[it]?.category, overrides) }
        }
    }
}
