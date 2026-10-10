package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
}

private class RepositoryAppSource(private val usage: UsageRepository, private val id: DeviceId, private val packageName: String) : AppSource {
    override fun today(): LocalDate = usage.today()

    override fun app(range: DetailRange): Flow<AppDetail?> = usage.app(id, packageName, range)

    override suspend fun setCategory(category: AppCategory?) = usage.setCategory(packageName, category)
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
}
