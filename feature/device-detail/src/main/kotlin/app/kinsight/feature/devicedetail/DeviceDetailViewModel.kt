package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Period
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** What the detail view model reads: the viewer's date and one range of the device. */
internal interface DetailSource {
    fun today(): LocalDate

    fun detail(range: DetailRange): Flow<DeviceDetail?>
}

private class RepositorySource(private val usage: UsageRepository, private val id: DeviceId, private val weekFields: WeekFields) : DetailSource {
    override fun today(): LocalDate = usage.today()

    override fun detail(range: DetailRange): Flow<DeviceDetail?> = usage.detail(id, range, weekFields)
}

/**
 * The detail screen's range and its detail (spec §9.3, stage 5a): it starts on the WEEK containing today (or
 * [initial]) and moves as [RangeNavigator] describes; period, anchor and following survive process death in [saved].
 */
class DeviceDetailViewModel internal constructor(
    source: DetailSource,
    saved: SavedStateHandle,
    weekFields: WeekFields,
    initial: DetailRange? = null,
    ticks: Flow<Unit> = minuteTicks(),
) : ViewModel() {
    constructor(
        usage: UsageRepository,
        id: DeviceId,
        saved: SavedStateHandle,
        weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
        initial: DetailRange? = null,
    ) : this(RepositorySource(usage, id, weekFields), saved, weekFields, initial)

    private val nav = RangeNavigator(viewModelScope, source::today, source::detail, saved, weekFields, initial, ticks)

    val range: StateFlow<DetailRange> = nav.range

    val detail: StateFlow<DeviceDetail?> = nav.detail

    /** Another period around the same anchor; going to DAY picks the range's last known day up to today. */
    fun select(p: Period) = nav.select(p)

    fun back() = nav.back()

    fun forward() = nav.forward()

    /** A tapped bar of a week or month: that day. */
    fun openDay(date: LocalDate) = nav.openDay(date)

    internal companion object {
        const val KEY_PERIOD = RangeNavigator.KEY_PERIOD
        const val KEY_ANCHOR = RangeNavigator.KEY_ANCHOR
        const val KEY_FOLLOW = RangeNavigator.KEY_FOLLOW
    }
}
