package app.kinsight.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.GroupRepository
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LocalReset
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.ServiceRepository
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SettingsRepository
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something the settings screen tried and could not do; shown until dismissed or the next attempt. */
enum class SettingsError { SHARING, RETENTION, NOTIFICATIONS, RESET }

data class SettingsUi(
    val group: GroupState = GroupState.Loading,
    val usage: UsageSettings = UsageSettings(),
    val prefs: Prefs = Prefs(),
    val service: ServiceStatus? = null,
    val error: SettingsError? = null,
    /** A local wipe is running (LocalReset.status, app-wide); the reset button is disabled meanwhile. */
    val resetting: Boolean = false,
)

/** What the settings screen needs from core/data; a seam for tests. */
internal interface SettingsActions {
    val group: Flow<GroupState>
    val usage: Flow<UsageSettings>
    val prefs: Flow<Prefs>
    val service: Flow<ServiceStatus>

    suspend fun setSharing(on: Boolean)

    suspend fun setRetention(detailDays: Int, summaryDays: Int)

    suspend fun setSummaryMode(mode: SummaryMode)

    suspend fun setNoContactHours(hours: Int)

    /** The «Start» button only (SyncControl.startByUser): the one thing that clears a user's Stop. */
    fun startByUser()

    fun requestStop()

    /** LocalReset.status: app-wide, so it outlives this view model. */
    val resetStatus: Flow<ResetStatus>

    /** LocalReset.launchResetIdentity: on the app scope; joins a wipe already running. */
    fun launchReset()

    fun clearResetFailure()
}

private class RepositoryActions(
    group: GroupRepository,
    private val settings: SettingsRepository,
    private val serviceRepo: ServiceRepository,
    private val reset: LocalReset,
) : SettingsActions {
    override val group = group.state
    override val usage = settings.usageSettings
    override val prefs = settings.appPrefs
    override val service = serviceRepo.status

    override suspend fun setSharing(on: Boolean) = settings.setSharing(on)

    /** SettingsStore (DataStore, IO) then Pruner (Room, IO; the group file on Dispatchers.IO). */
    override suspend fun setRetention(detailDays: Int, summaryDays: Int) = settings.setRetention(detailDays, summaryDays)

    override suspend fun setSummaryMode(mode: SummaryMode) = settings.setSummaryMode(mode)

    override suspend fun setNoContactHours(hours: Int) = settings.setNoContactHours(hours)

    override fun startByUser() = serviceRepo.start()

    override fun requestStop() = serviceRepo.stop()

    override val resetStatus = reset.status

    /** LocalReset: stopAndWait → forget the group files → pipeline.exclusive { wipe; settings reset; new keys }. */
    override fun launchReset() {
        reset.launchResetIdentity()
    }

    override fun clearResetFailure() = reset.clearFailure()
}

/**
 * The settings screen's state and actions. Nothing here starts the sync service: opening settings never clears a
 * user's Stop, only [startSync] does. The identity reset runs in LocalReset on the app scope and its status is
 * app-wide, so leaving the screen mid-wipe neither cancels it nor loses its failure. Every failure is shown.
 */
class SettingsViewModel internal constructor(private val actions: SettingsActions) : ViewModel() {
    constructor(group: GroupRepository, settings: SettingsRepository, service: ServiceRepository, reset: LocalReset) :
        this(RepositoryActions(group, settings, service, reset))

    private val _error = MutableStateFlow<SettingsError?>(null)

    /** The last failed setting change (not the reset: that one is in LocalReset.status). */
    val error: StateFlow<SettingsError?> = _error.asStateFlow()

    private val data = combine(
        actions.group,
        actions.usage,
        actions.prefs,
        actions.service.map<ServiceStatus, ServiceStatus?> { it }.onStart { emit(null) },
    ) { g, u, p, s -> SettingsUi(g, u, p, s) }

    val ui: StateFlow<SettingsUi> = combine(data, _error, actions.resetStatus) { d, e, r ->
        val resetFailed = r is ResetStatus.Failed && r.kind == ResetKind.NEW_IDENTITY
        d.copy(error = e ?: SettingsError.RESET.takeIf { resetFailed }, resetting = r is ResetStatus.Running)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    /** A consenting child only; the pipeline signs SHARING_PAUSED / SHARING_RESUMED first, so the family is told. */
    fun setSharing(on: Boolean) = attempt(SettingsError.SHARING, "setSharing($on)") { actions.setSharing(on) }

    fun setRetention(detail: Int, summary: Int) = attempt(SettingsError.RETENTION, "setRetention($detail, $summary)") {
        actions.setRetention(detail, summary)
    }

    fun setSummaryMode(mode: SummaryMode) = attempt(SettingsError.NOTIFICATIONS, "setSummaryMode($mode)") { actions.setSummaryMode(mode) }

    fun setNoContactHours(hours: Int) = attempt(SettingsError.NOTIFICATIONS, "setNoContactHours($hours)") {
        actions.setNoContactHours(hours)
    }

    fun stopSync() = actions.requestStop()

    fun startSync() = actions.startByUser()

    fun dismissError() {
        if (_error.value != null) _error.value = null else actions.clearResetFailure()
    }

    /** On success the group state reads NoGroup and the app returns to the first run. */
    fun resetIdentity() {
        _error.value = null
        actions.launchReset()
    }

    private fun attempt(onFailure: SettingsError, what: String, block: suspend () -> Unit) {
        _error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$what failed", e)
                _error.value = onFailure
            }
        }
    }

    private companion object {
        const val TAG = "KinsightSettings"
    }
}
