package app.kinsight.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ServiceRepository
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SettingsRepository
import app.kinsight.core.data.UsageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [noContactHours]: the user's «no contact» setting (spec §9.7), so a card and the alert agree (preflight C19). */
data class DashboardUi(
    val dashboard: DashboardState? = null,
    val service: ServiceStatus? = null,
    val loaded: Boolean = false,
    val noContactHours: Int = Prefs().noContactHours,
)

class DashboardViewModel(
    usage: UsageRepository,
    private val service: ServiceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val noContactHours = settings.appPrefs.map { it.noContactHours }.catch { emit(Prefs().noContactHours) }

    val ui: StateFlow<DashboardUi> = combine(usage.dashboard, service.status, noContactHours) { d, s, h -> DashboardUi(d, s, loaded = true, noContactHours = h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    /** Signs SHARING_PAUSED (a consenting child only; the pipeline refuses otherwise): the family is told. */
    fun pause() {
        viewModelScope.launch { runCatching { settings.setSharing(false) } }
    }

    fun resume() {
        viewModelScope.launch { runCatching { settings.setSharing(true) } }
    }

    fun start() = service.start()

    fun syncNow() = service.syncNow()
}
