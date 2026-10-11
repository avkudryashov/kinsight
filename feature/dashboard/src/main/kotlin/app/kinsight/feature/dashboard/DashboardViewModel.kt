package app.kinsight.feature.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.NewParents
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceRepository
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SettingsRepository
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** The last pause or resume failed (nothing was signed); cleared by the next attempt. */
    val sharingFailed: Boolean = false,
    /** On a child's phone: parents it has not acknowledged yet (U7 banner). */
    val newParents: List<MemberView> = emptyList(),
)

class DashboardViewModel(
    usage: UsageRepository,
    private val service: ServiceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val noContactHours = settings.appPrefs.map { it.noContactHours }.catch { emit(Prefs().noContactHours) }

    private val sharingFailed = MutableStateFlow(false)

    // A read failure shows no banner (null: nothing stored yet). The baseline is the alerts pass's job
    // (AlertsRun.baselineParents, the planner's known parents), so a parent added before this child first opens
    // Overview is still news here.
    private val acknowledged = settings.acknowledgedParents.catch { emit(null) }

    val ui: StateFlow<DashboardUi> = combine(usage.dashboard, service.status, noContactHours, sharingFailed, acknowledged) { d, s, h, f, ack ->
        val parents = d?.takeIf { it.self.role == Role.CHILD }?.parents // a child's phone only
        DashboardUi(d, s, loaded = true, noContactHours = h, sharingFailed = f, newParents = parents?.let { NewParents.of(it, ack) }.orEmpty())
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    /** «Понятно» on the banner: the [shown] parents (the ones it listed) are known now; one who arrived since is still news. */
    fun acknowledgeParents(shown: Set<DeviceId>) {
        if (shown.isEmpty()) return
        viewModelScope.launch {
            try {
                settings.acknowledgeParents(shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "acknowledgeParents failed", e)
            }
        }
    }

    /** Signs SHARING_PAUSED (a consenting child only; the pipeline refuses otherwise): the family is told. */
    fun pause() = setSharing(false)

    fun resume() = setSharing(true)

    private fun setSharing(on: Boolean) {
        sharingFailed.value = false
        viewModelScope.launch {
            try {
                settings.setSharing(on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setSharing($on) failed", e)
                sharingFailed.value = true
            }
        }
    }

    private companion object {
        const val TAG = "KinsightDashboard"
    }

    fun start() = service.start()

    fun syncNow() = service.syncNow()

    /** ON_RESUME: re-reads permissions and usage access at once (ServiceRepository.refresh). */
    fun refreshStatus() = service.refresh()
}
