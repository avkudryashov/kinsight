package app.kinsight.feature.devicedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModel(usage: UsageRepository, id: DeviceId, initial: Period = Period.WEEK) : ViewModel() {
    private val period = MutableStateFlow(initial)

    val detail: StateFlow<DeviceDetail?> = period.flatMapLatest { usage.detail(id, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(p: Period) {
        period.value = p
    }
}
