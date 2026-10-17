package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Period
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** What the detail view model reads: the viewer's date and one range of the device. */
internal interface DetailSource {
    fun today(): LocalDate

    /** False while the group is not readable (no group, key or storage error): a null view is then not «missing». */
    fun groupReady(): Flow<Boolean> = flowOf(true)

    fun detail(range: DetailRange): Flow<DeviceDetail?>

    /** Only a parent sets thresholds (spec §9.7, stage 5b). */
    fun viewerIsParent(): Flow<Boolean> = flowOf(false)

    /** The parent's rules of this device. */
    fun thresholds(): Flow<List<ThresholdRule>> = flowOf(emptyList())
}

private class RepositorySource(private val usage: UsageRepository, private val id: DeviceId) : DetailSource {
    override fun today(): LocalDate = usage.today()

    override fun detail(range: DetailRange): Flow<DeviceDetail?> = usage.detail(id, range)

    override fun groupReady(): Flow<Boolean> = usage.groupReady

    override fun viewerIsParent(): Flow<Boolean> = usage.viewerIsParent

    override fun thresholds(): Flow<List<ThresholdRule>> = usage.thresholds.map { rules -> rules.filter { it.child == id } }
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
    defaultPeriod: Period = Period.WEEK,
) : ViewModel() {
    constructor(
        usage: UsageRepository,
        id: DeviceId,
        saved: SavedStateHandle,
        weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
        initial: DetailRange? = null,
    ) : this(RepositorySource(usage, id), saved, weekFields, initial, defaultPeriod = Period.DAY)

    private val nav = RangeNavigator(viewModelScope, source::today, source::detail, saved, weekFields, initial, ticks, defaultPeriod = defaultPeriod)

    val range: StateFlow<DetailRange> = nav.range

    val detail: StateFlow<DeviceDetail?> = nav.detail

    /** The first view arrived while the group is readable: a null detail is then «no such device», not an error or loading. */
    val loaded: StateFlow<Boolean> = combine(nav.loaded, source.groupReady()) { l, ready -> l && ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The device's thresholds on a parent's phone (the «Limits» entry and the total's line); null on a child's. */
    val thresholds: StateFlow<List<ThresholdRule>?> = combine(source.viewerIsParent(), source.thresholds()) { parent, rules ->
        rules.takeIf { parent }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
