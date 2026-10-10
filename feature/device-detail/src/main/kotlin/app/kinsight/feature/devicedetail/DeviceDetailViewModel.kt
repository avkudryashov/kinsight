package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Period
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
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

/** Every minute: the day can change while the screen is open. */
private fun minuteTicks(): Flow<Unit> = flow {
    while (true) {
        delay(60_000)
        emit(Unit)
    }
}

/**
 * The detail screen's range (spec §9.3, stage 5a): it starts on the WEEK containing today and moves with [back],
 * [forward], [select] and [openDay], never past the child's today ([DeviceDetail.today], else the viewer's) nor before
 * the oldest summary ([DeviceDetail.canGoBack]). A range that contains today follows it: when the date changes while
 * the screen is open (or the child's zone is already a day ahead) it moves to the range containing the new today; a
 * range the user stepped back to stays. Period, anchor and following survive process death in [saved].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModel internal constructor(
    private val source: DetailSource,
    private val saved: SavedStateHandle,
    private val weekFields: WeekFields,
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

    private val _range = MutableStateFlow(initial ?: restored() ?: DetailRange.of(Period.WEEK, source.today(), weekFields))
    private var follows: Boolean = saved[KEY_FOLLOW] ?: (initial == null && !_range.value.end.isBefore(source.today()))

    val range: StateFlow<DetailRange> = _range.asStateFlow()

    val detail: StateFlow<DeviceDetail?> = _range.flatMapLatest { source.detail(it) }
        .onEach { reanchor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        save()
        viewModelScope.launch { ticks.collect { reanchor(detail.value) } }
    }

    /** Another period around the same anchor; going to DAY picks the range's last known day up to today. */
    fun select(p: Period) {
        val current = _range.value
        if (p == current.period) return
        set(DetailRange.of(p, anchor(), weekFields))
    }

    fun back() {
        val d = current() ?: return
        if (d.canGoBack) set(_range.value.previous())
    }

    fun forward() {
        val next = _range.value.next()
        if (!next.start.isAfter(today())) set(next)
    }

    /** A tapped bar of a week or month: that day. */
    fun openDay(date: LocalDate) {
        if (!date.isAfter(today())) set(DetailRange.of(Period.DAY, date, weekFields))
    }

    /** The loaded detail of the current range, not a previous one still on screen. */
    private fun current(): DeviceDetail? = detail.value?.takeIf { it.range == _range.value }

    private fun today(): LocalDate = maxOf(source.today(), detail.value?.today ?: source.today())

    private fun anchor(): LocalDate {
        val today = today()
        val r = _range.value
        return current()?.days?.lastOrNull { it.known && !it.date.isAfter(today) }?.date ?: minOf(r.end, today).coerceAtLeast(r.start)
    }

    private fun reanchor(d: DeviceDetail?) {
        val today = maxOf(source.today(), d?.today ?: source.today())
        val r = _range.value
        if (follows && r.end.isBefore(today)) set(DetailRange.of(r.period, today, weekFields))
    }

    private fun set(r: DetailRange) {
        follows = !r.end.isBefore(today())
        _range.value = r
        save()
    }

    private fun save() {
        saved[KEY_PERIOD] = _range.value.period.name
        saved[KEY_ANCHOR] = _range.value.start.toString()
        saved[KEY_FOLLOW] = follows
    }

    private fun restored(): DetailRange? {
        val period = saved.get<String>(KEY_PERIOD)?.let { p -> Period.entries.firstOrNull { it.name == p } } ?: return null
        val anchor = saved.get<String>(KEY_ANCHOR)?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: return null
        return DetailRange.of(period, anchor, weekFields)
    }

    internal companion object {
        const val KEY_PERIOD = "detail_period"
        const val KEY_ANCHOR = "detail_anchor"
        const val KEY_FOLLOW = "detail_follows"
    }
}
