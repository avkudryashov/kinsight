package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.Categories
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Days of apps the «app» picker offers (spec §9.7, stage 5b): the child's apps of the last 30 days. */
internal const val PICKER_DAYS = 30L

/** What the thresholds view model reads and writes: the parent's rules, whether this phone is a parent, the child's recent apps. */
internal interface ThresholdSource {
    /** Every listed rule (children of the group); the view model keeps its child's. */
    fun rules(): Flow<List<ThresholdRule>>

    fun viewerIsParent(): Flow<Boolean>

    /** The child's apps of the last [PICKER_DAYS] days, largest first. */
    fun apps(): Flow<AppList?>

    suspend fun set(rule: ThresholdRule)

    suspend fun remove(id: String)
}

private class RepositoryThresholdSource(private val usage: UsageRepository, private val id: DeviceId) : ThresholdSource {
    override fun rules(): Flow<List<ThresholdRule>> = usage.thresholds

    override fun viewerIsParent(): Flow<Boolean> = usage.viewerIsParent

    override fun apps(): Flow<AppList?> {
        val today = usage.today()
        return usage.apps(id, DetailRange(Period.MONTH, today.minusDays(PICKER_DAYS - 1), today))
    }

    override suspend fun set(rule: ThresholdRule) = usage.setThreshold(rule)

    override suspend fun remove(id: String) = usage.removeThreshold(id)
}

/**
 * The thresholds screen of one child: [rules] total first, then categories (AppCategory order), then apps by label;
 * [apps] the child's apps of the last 30 days a rule may name (not the `~other` remainder); [canEdit] only on a parent.
 */
data class ThresholdsState(val childName: String, val canEdit: Boolean, val rules: List<ThresholdRule>, val apps: List<AppUsage>) {
    /** An app rule's label: the app's own when it is in [apps], else its package. */
    fun appLabel(packageName: String): String = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
}

/**
 * The parent's daily limits for child [child] (spec §9.7, stage 5b): local, only notify. Writes are refused on a
 * child's phone ([ThresholdsState.canEdit]) and for anything [ThresholdRule.of] refuses.
 *
 * A delete is only hidden ([delete]) while its «Undo» snackbar is up and written by [commitDelete] when it goes
 * without «Undo», or when the view model is cleared first (on [commitScope], which outlives it): the alert planner
 * drops a removed rule's «already notified» markers, so removing at once and restoring on «Undo» could notify twice
 * the same day.
 */
class ThresholdsViewModel internal constructor(
    private val source: ThresholdSource,
    private val child: DeviceId,
    private val commitScope: CoroutineScope,
) : ViewModel() {
    constructor(usage: UsageRepository, child: DeviceId, commitScope: CoroutineScope) :
        this(RepositoryThresholdSource(usage, child), child, commitScope)

    /** Ids deleted on screen but not yet removed from storage. */
    private val pending = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<ThresholdsState?> = combine(source.rules(), source.viewerIsParent(), source.apps(), pending) { all, parent, list, hidden ->
        val rules = all.filter { it.id !in hidden }
        val apps = list?.apps.orEmpty().filter { it.packageName != Categories.REMAINDER_PACKAGE }
        val labels = apps.associate { it.packageName to it.label }
        val mine = rules.filter { it.child == child }.sortedWith(
            compareBy<ThresholdRule>({ order(it.target) }, { (it.target as? ThresholdTarget.App)?.let { t -> labels[t.packageName] ?: t.packageName } }),
        )
        ThresholdsState(list?.member?.name.orEmpty(), parent, mine, apps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val canEdit get() = state.value?.canEdit == true

    /** Adds the rule of [target] or changes its limit; false when refused (not a parent, or not a valid rule). */
    fun save(target: ThresholdTarget, limitMs: Long): Boolean {
        if (!canEdit) return false
        val rule = ThresholdRule.of(child, target, limitMs) ?: return false
        viewModelScope.launch { source.set(rule) }
        return true
    }

    /** Hides [rule] while its «Undo» is offered; storage is untouched until [commitDelete]. */
    fun delete(rule: ThresholdRule) {
        if (!canEdit || rule.child != child) return
        pending.update { it + rule.id }
    }

    /** The «Undo» window of [rule] ended without «Undo»: remove it from storage. */
    fun commitDelete(rule: ThresholdRule) {
        if (rule.id !in pending.value) return
        commitScope.launch {
            source.remove(rule.id)
            pending.update { it - rule.id }
        }
    }

    /** Shows a just deleted [rule] again (the snackbar's «Undo»); it was never removed. */
    fun undo(rule: ThresholdRule) {
        pending.update { it - rule.id }
    }

    /** Leaving the screen before a snackbar went: its delete stands. */
    override fun onCleared() {
        val ids = pending.value
        if (ids.isEmpty()) return
        commitScope.launch { ids.forEach { source.remove(it) } }
    }

    private fun order(t: ThresholdTarget): Int = when (t) {
        ThresholdTarget.Total -> -1
        is ThresholdTarget.Category -> t.category.ordinal
        is ThresholdTarget.App -> Int.MAX_VALUE
    }
}

/** The value picker's grid (spec §9.7): 15 minutes to 12 hours in 15-minute steps. */
object ThresholdValues {
    const val DEFAULT_MS = 3_600_000L

    fun canUp(ms: Long): Boolean = ms < ThresholdRule.MAX_MS

    fun canDown(ms: Long): Boolean = ms > ThresholdRule.MIN_MS

    fun up(ms: Long): Long = clamp(ms + ThresholdRule.STEP_MS)

    fun down(ms: Long): Long = clamp(ms - ThresholdRule.STEP_MS)

    /** [ms] rounded down to the step and kept within the bounds. */
    fun clamp(ms: Long): Long = (ms - Math.floorMod(ms, ThresholdRule.STEP_MS)).coerceIn(ThresholdRule.MIN_MS, ThresholdRule.MAX_MS)
}
