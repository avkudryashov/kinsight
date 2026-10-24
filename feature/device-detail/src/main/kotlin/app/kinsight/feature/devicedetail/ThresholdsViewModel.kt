package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.Categories
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.RuleMode
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Days of apps the «app» picker offers (spec §9.7, stage 5b): the child's apps of the last 30 days. */
internal const val PICKER_DAYS = 30L

/** What the thresholds view model reads and writes: the parent's rules, whether this phone is a parent, the child's recent apps. */
internal interface ThresholdSource {
    /** Every listed rule (children of the group); the view model keeps its child's. */
    fun rules(): Flow<List<ThresholdRule>>

    fun viewerIsParent(): Flow<Boolean>

    /** The child's apps of the last [PICKER_DAYS] days, largest first. */
    fun apps(): Flow<AppList?>

    /** The child's time today for the «Всего за день» bar (UI refresh §7); null without a summary of today. */
    fun todayMs(): Flow<Long?> = flowOf(null)

    /** The child's accessibility service is off, so blocks are not enforced there (fed from Task 9; false until then). */
    fun blockingOff(): Flow<Boolean> = flowOf(false)

    /** Blocks wait for the child to agree to the updated consent (fed from Task 10; false until then). */
    fun awaitingConsent(): Flow<Boolean> = flowOf(false)

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

    override fun todayMs(): Flow<Long?> = usage.dashboard.map { d ->
        d?.children?.firstOrNull { it.member.id == id }?.today?.takeIf { it.known }?.totalMs
    }

    override suspend fun set(rule: ThresholdRule) = usage.setThreshold(rule)

    override suspend fun remove(id: String) = usage.removeThreshold(id)
}

/**
 * The rules screen of one child ([child]): [rules] total first, then categories (AppCategory order), then apps by
 * label; [apps] the child's apps of the last 30 days a rule may name (not the `~other` remainder); [canEdit] only on a
 * parent. [blockingOff] and [awaitingConsent] explain why blocks do not act yet.
 */
data class ThresholdsState(
    val childName: String,
    val canEdit: Boolean,
    val rules: List<ThresholdRule>,
    val apps: List<AppUsage>,
    val todayMs: Long? = null,
    val blockingOff: Boolean = false,
    val awaitingConsent: Boolean = false,
    val child: DeviceId,
) {
    val blocked get() = rules.filter { it.mode == RuleMode.BLOCK }
    val limits get() = rules.filter { it.mode == RuleMode.LIMIT }
    val allowed get() = rules.filter { it.mode == RuleMode.ALLOW }

    /** An app rule's label: the app's own when it is in [apps], else its package. */
    fun appLabel(packageName: String): String = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
}

/**
 * The parent's rules for child [child] (spec §4, §9.7): limits, blocks and allows. Writes are refused on a child's
 * phone ([ThresholdsState.canEdit]) and for any rule the builders of [ThresholdRule] would refuse.
 *
 * A delete is only hidden ([delete]) while its «Undo» snackbar is up and written by [commitDelete] when it goes
 * without «Undo», or when the view model is cleared first (on [commitScope], which outlives it): the alert planner
 * drops a removed rule's «already notified» markers, so removing at once and restoring on «Undo» could notify twice
 * the same day.
 *
 * The pending deletes and the end of their window live in [saved], so a recreated screen (rotation
 * or process death) offers their «Undo» again ([pendingDeletes]), and one whose window passed meanwhile is committed.
 * Every storage write (save, commit) runs on [writes], one serial lane on [commitScope], so a delete committed just
 * before a save of the same target can never land after it.
 */
class ThresholdsViewModel internal constructor(
    private val source: ThresholdSource,
    private val child: DeviceId,
    private val commitScope: CoroutineScope,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val writes: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    constructor(usage: UsageRepository, child: DeviceId, commitScope: CoroutineScope, saved: SavedStateHandle) :
        this(RepositoryThresholdSource(usage, child), child, commitScope, saved, writes = ThresholdWrites.lane)

    /** Deleted on screen, «Undo» still offered: id to the end of its window (wall clock ms). */
    private val pending: MutableStateFlow<Map<String, Long>>

    /** Window over, the storage remove queued on [writes]: still hidden until it is done. */
    private val committing = MutableStateFlow<Set<String>>(emptySet())

    init {
        val (live, passed) = restore().entries.partition { it.value > clock() }
        pending = MutableStateFlow(live.associate { it.key to it.value })
        persist()
        passed.forEach { remove(it.key) }
    }

    /** The deletes whose «Undo» the screen offers (each with its own snackbar), also after a recreation. */
    val pendingDeletes: StateFlow<Set<String>> =
        pending.map { it.keys }.stateIn(viewModelScope, SharingStarted.Eagerly, pending.value.keys)

    private val flags = combine(source.blockingOff(), source.awaitingConsent(), ::Pair)

    val state: StateFlow<ThresholdsState?> =
        combine(source.rules(), source.viewerIsParent(), source.apps(), pending, combine(committing, source.todayMs(), flags, ::Triple)) { all, parent, list, open, (queued, today, flag) ->
            val rules = all.filter { it.id !in open && it.id !in queued }
            val apps = list?.apps.orEmpty().filter { it.packageName != Categories.REMAINDER_PACKAGE }
            val labels = apps.associate { it.packageName to it.label }
            val mine = rules.filter { it.child == child }.sortedWith(
                compareBy<ThresholdRule>({ order(it.target) }, { (it.target as? ThresholdTarget.App)?.let { t -> labels[t.packageName] ?: t.packageName } }),
            )
            ThresholdsState(list?.member?.name.orEmpty(), parent, mine, apps, today, flag.first, flag.second, child)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val canEdit get() = state.value?.canEdit == true

    /**
     * Adds [rule] or replaces the rule of its target (one rule per target: its id is the same whatever the mode); false
     * when refused (not a parent, another child's rule, or not a valid rule).
     */
    fun save(rule: ThresholdRule): Boolean {
        if (!canEdit || rule.child != child || !rule.isValid) return false
        // A rule of the same target saved inside its delete's «Undo» window: the delete is off ([commitDelete] returns).
        setPending { it - rule.id }
        // On [commitScope]: leaving the screen right away must not cancel the write; after any queued remove.
        commitScope.launch(writes) { source.set(rule) }
        return true
    }

    /** Hides [rule] while its «Undo» is offered; storage is untouched until [commitDelete]. */
    fun delete(rule: ThresholdRule) {
        if (!canEdit || rule.child != child) return
        val until = clock() + UNDO_WINDOW_MS
        setPending { it + (rule.id to until) }
    }

    /** The «Undo» window of rule [id] ended without «Undo»: remove it from storage. */
    fun commitDelete(id: String) {
        if (id !in pending.value) return
        setPending { it - id }
        remove(id)
    }

    fun commitDelete(rule: ThresholdRule) = commitDelete(rule.id)

    /**
     * The snackbar of rule [id] has just appeared: its window starts now. A second delete waits behind the first
     * snackbar, so the window set at [delete] would end early; a recreation honours the window from here.
     */
    fun offered(id: String) {
        if (id !in pending.value) return
        val until = clock() + UNDO_WINDOW_MS
        setPending { if (id in it) it + (id to until) else it }
    }

    /** Shows a just deleted rule [id] again (the snackbar's «Undo»); it was never removed. */
    fun undo(id: String) {
        setPending { it - id }
    }

    fun undo(rule: ThresholdRule) = undo(rule.id)

    /** Leaving the screen before a snackbar went: its delete stands. */
    override fun onCleared() {
        val ids = pending.value.keys
        if (ids.isEmpty()) return
        commitScope.launch(writes) { ids.forEach { source.remove(it) } }
    }

    private fun remove(id: String) {
        committing.update { it + id }
        commitScope.launch(writes) {
            try {
                source.remove(id)
            } finally {
                committing.update { it - id }
            }
        }
    }

    private fun setPending(change: (Map<String, Long>) -> Map<String, Long>) {
        pending.update(change)
        persist()
    }

    /** As «end of window|id» lines: a Bundle keeps a string list; an id never holds the separator first. */
    private fun persist() {
        saved[KEY_PENDING] = ArrayList(pending.value.map { (id, until) -> "$until|$id" })
    }

    private fun restore(): Map<String, Long> = saved.get<ArrayList<String>>(KEY_PENDING).orEmpty().mapNotNull { line ->
        val cut = line.indexOf('|')
        val until = line.take(cut.coerceAtLeast(0)).toLongOrNull() ?: return@mapNotNull null
        line.substring(cut + 1).takeIf { it.isNotEmpty() }?.let { it to until }
    }.toMap()

    private fun order(t: ThresholdTarget): Int = when (t) {
        ThresholdTarget.Total -> -1
        is ThresholdTarget.Category -> t.category.ordinal
        is ThresholdTarget.App -> Int.MAX_VALUE
    }

    companion object {
        /** How long a delete's «Undo» is offered: the snackbar's long duration. */
        const val UNDO_WINDOW_MS = 10_000L

        private const val KEY_PENDING = "thr_pending_deletes"
    }
}

/**
 * The one serial lane for threshold writes (both screens), on the app scope: storage edits are queued in the order
 * the user made them, so an older delete never removes a rule saved after it.
 */
internal object ThresholdWrites {
    val lane: CoroutineContext = Dispatchers.Default.limitedParallelism(1)
}

/** The value picker's grid (spec §9.7): 15 minutes to 12 hours in 15-minute steps. */
object ThresholdValues {
    const val DEFAULT_MS = 3_600_000L

    /** Quick choices next to −/+ (U15): 30 min, 1 h, 2 h, 3 h. */
    val PRESETS_MS = listOf(30L, 60, 120, 180).map { it * 60_000L }

    fun canUp(ms: Long): Boolean = ms < ThresholdRule.MAX_MS

    fun canDown(ms: Long): Boolean = ms > ThresholdRule.MIN_MS

    fun up(ms: Long): Long = clamp(ms + ThresholdRule.STEP_MS)

    fun down(ms: Long): Long = clamp(ms - ThresholdRule.STEP_MS)

    /** [ms] rounded down to the step and kept within the bounds. */
    fun clamp(ms: Long): Long = (ms - Math.floorMod(ms, ThresholdRule.STEP_MS)).coerceIn(ThresholdRule.MIN_MS, ThresholdRule.MAX_MS)
}
