package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** What the app view model reads and writes: the viewer's date, one range of the app, the app's category override. */
internal interface AppSource {
    fun today(): LocalDate

    fun app(range: DetailRange): Flow<AppDetail?>

    /** null returns the app to its own category («По умолчанию»). */
    suspend fun setCategory(category: AppCategory?)

    /** The parent's limit of this app (spec §9.7, stage 5b), null without one. */
    fun threshold(): Flow<ThresholdRule?> = flowOf(null)

    suspend fun setThreshold(rule: ThresholdRule) {}

    suspend fun removeThreshold(id: String) {}
}

private class RepositoryAppSource(private val usage: UsageRepository, private val id: DeviceId, private val packageName: String) : AppSource {
    override fun today(): LocalDate = usage.today()

    override fun app(range: DetailRange): Flow<AppDetail?> = usage.app(id, packageName, range)

    override suspend fun setCategory(category: AppCategory?) = usage.setCategory(packageName, category)

    private val ruleId = ThresholdRule.idOf(id, ThresholdTarget.App(packageName))

    override fun threshold(): Flow<ThresholdRule?> = usage.thresholds.map { rules -> rules.firstOrNull { it.id == ruleId } }

    override suspend fun setThreshold(rule: ThresholdRule) = usage.setThreshold(rule)

    override suspend fun removeThreshold(id: String) = usage.removeThreshold(id)
}

/**
 * The app screen (spec §9.3, stage 5a): opens on the range the detail screen showed ([initial]) and navigates like it
 * ([RangeNavigator]); a range saved before process death wins over the route's argument. [setCategory] writes the
 * parent's local override; the flows re-emit, so this screen and the detail's level 3 update.
 */
class AppDetailViewModel internal constructor(
    private val source: AppSource,
    saved: SavedStateHandle,
    weekFields: WeekFields,
    initial: DetailRange,
    ticks: Flow<Unit> = minuteTicks(),
) : ViewModel() {
    constructor(
        usage: UsageRepository,
        id: DeviceId,
        packageName: String,
        initial: DetailRange,
        saved: SavedStateHandle,
        weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
    ) : this(RepositoryAppSource(usage, id, packageName), saved, weekFields, initial)

    private val nav = RangeNavigator(viewModelScope, source::today, source::app, saved, weekFields, initial, ticks, restoreFirst = true)

    val range: StateFlow<DetailRange> = nav.range

    val detail: StateFlow<AppDetail?> = nav.detail

    /** This app's limit («Notify if more than…»). */
    val threshold: StateFlow<ThresholdRule?> = source.threshold().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(p: Period) = nav.select(p)

    fun back() = nav.back()

    fun forward() = nav.forward()

    /** A tapped bar of a week or month: that day. */
    fun openDay(date: LocalDate) = nav.openDay(date)

    /** Only when [AppDetail.canChangeCategory] (a parent, not `~other`); null clears the override. */
    fun setCategory(category: AppCategory?) {
        if (detail.value?.canChangeCategory != true) return
        viewModelScope.launch { source.setCategory(category) }
    }

    /** Only where [canSetThreshold]; a limit [ThresholdRule.of] refuses is ignored. */
    fun setThreshold(limitMs: Long) {
        val d = detail.value?.takeIf(::canSetThreshold) ?: return
        val rule = ThresholdRule.of(d.member.id, ThresholdTarget.App(d.packageName), limitMs) ?: return
        viewModelScope.launch { source.setThreshold(rule) }
    }

    fun removeThreshold() {
        val d = detail.value?.takeIf(::canSetThreshold) ?: return
        viewModelScope.launch { source.removeThreshold(ThresholdRule.idOf(d.member.id, ThresholdTarget.App(d.packageName))) }
    }
}

/** A parent's phone (as for the category: not `~other`) and a child's app: «Notify if more than…» is offered. */
internal fun canSetThreshold(app: AppDetail): Boolean = app.canChangeCategory && app.member.role == Role.CHILD
