package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.Period
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.temporal.WeekFields
import java.util.Locale

/** The period containing today (calendar week by the locale, calendar month); navigation between ranges comes later. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModel(
    private val usage: UsageRepository,
    id: DeviceId,
    initial: Period = Period.WEEK,
    private val weekFields: WeekFields = WeekFields.of(Locale.getDefault()),
) : ViewModel() {
    private val range = MutableStateFlow(rangeOf(initial))

    val detail: StateFlow<DeviceDetail?> = range.flatMapLatest { usage.detail(id, it, weekFields) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(p: Period) {
        range.value = rangeOf(p)
    }

    private fun rangeOf(p: Period) = DetailRange.of(p, usage.today(), weekFields)
}
