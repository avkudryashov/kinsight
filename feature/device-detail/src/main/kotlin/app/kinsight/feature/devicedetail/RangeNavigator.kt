package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.RangedView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.WeekFields

/** Every minute: the day can change while the screen is open. */
internal fun minuteTicks(): Flow<Unit> = flow {
    while (true) {
        delay(60_000)
        emit(Unit)
    }
}

/**
 * A screen's range (spec §9.3, stage 5a), shared by the detail and the app screen: it starts on [initial] or the WEEK
 * containing today and moves with [back], [forward], [select] and [openDay], never past the child's today
 * ([RangedView.today], else the viewer's) nor before the oldest summary ([RangedView.canGoBack]). A range that contains
 * today follows it: when the date changes while the screen is open (or the child's zone is already a day ahead) it moves
 * to the range containing the new today; a range the user stepped back to stays. Every tick re-reads the viewer's date
 * and a new date resubscribes the view (midnight inside the same range refreshes it). Period, anchor and following survive
 * process death in [saved]; with [restoreFirst] a saved range wins over [initial] (a route that passes its argument
 * again after process death).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RangeNavigator<T : RangedView>(
    scope: CoroutineScope,
    private val viewerToday: () -> LocalDate,
    load: (DetailRange) -> Flow<T?>,
    private val saved: SavedStateHandle,
    private val weekFields: WeekFields,
    initial: DetailRange?,
    ticks: Flow<Unit>,
    restoreFirst: Boolean = false,
) {
    private val _range = MutableStateFlow(
        (if (restoreFirst) restored() ?: initial else initial ?: restored()) ?: DetailRange.of(Period.WEEK, viewerToday(), weekFields),
    )
    // A route that passes the range on screen (restoreFirst) follows today when that range contains it, like the detail.
    private var follows: Boolean = saved[KEY_FOLLOW]
        ?: ((restoreFirst || initial == null) && !_range.value.end.isBefore(viewerToday()))

    val range: StateFlow<DetailRange> = _range.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** The first view has arrived, null or not: a null after it means «no such device» (U12), before it «loading». */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The viewer's date, re-read on every tick; a change resubscribes the view so today, known days and the child's today are fresh. */
    private val dates: Flow<LocalDate> = ticks.map { viewerToday() }.onStart { emit(viewerToday()) }.distinctUntilChanged()

    val detail: StateFlow<T?> = combine(_range, dates) { r, _ -> r }
        .flatMapLatest { load(it) }
        .onEach {
            reanchor(it)
            _loaded.value = true
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        save()
    }

    /** Another period around the same anchor; going to DAY picks the range's last known day up to today. */
    fun select(p: Period) {
        if (p == _range.value.period) return
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

    /** The loaded view of the current range, not a previous one still on screen. */
    private fun current(): T? = detail.value?.takeIf { it.range == _range.value }

    private fun today(): LocalDate = maxOf(viewerToday(), detail.value?.today ?: viewerToday())

    private fun anchor(): LocalDate {
        val today = today()
        val r = _range.value
        return current()?.days?.lastOrNull { it.known && !it.date.isAfter(today) }?.date ?: minOf(r.end, today).coerceAtLeast(r.start)
    }

    private fun reanchor(d: T?) {
        val today = maxOf(viewerToday(), d?.today ?: viewerToday())
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

    companion object {
        const val KEY_PERIOD = "detail_period"
        const val KEY_ANCHOR = "detail_anchor"
        const val KEY_FOLLOW = "detail_follows"
    }
}
