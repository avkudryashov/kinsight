package app.kinsight.feature.settings

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinsight.core.data.ChildLock
import app.kinsight.core.data.ChildLockStatus
import app.kinsight.core.data.CloudRepository
import app.kinsight.core.data.CloudStatus
import app.kinsight.core.data.GroupRepository
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LocalReset
import app.kinsight.core.data.PinCheck
import app.kinsight.core.data.PinSave
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.ServiceRepository
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SettingsRepository
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.SignInMode
import app.kinsight.core.data.SignInState
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.data.UsageRepository
import app.kinsight.core.model.DeviceId
import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something the settings screen tried and could not do; shown until dismissed or the next attempt. */
enum class SettingsError { SHARING, RETENTION, NOTIFICATIONS, RESET, HANDOVER }

data class SettingsUi(
    val group: GroupState = GroupState.Loading,
    val usage: UsageSettings = UsageSettings(),
    val prefs: Prefs = Prefs(),
    val service: ServiceStatus? = null,
    val error: SettingsError? = null,
    /** A local wipe is running (LocalReset.status, app-wide); the reset button is disabled meanwhile. */
    val resetting: Boolean = false,
    /** This child's signed sharing state (DashboardState.selfUsage); null while the snapshot has none (U1). */
    val ownSharing: Sharing? = null,
    /** The cloud row (spec §6, §11); not shown while [CloudStatus.available] is false. */
    val cloud: CloudStatus = CloudStatus(),
    /** The sign-in dialog: shown unless [SignInState.Idle]. */
    val signIn: SignInState = SignInState.Idle,
    /** Spec §10: the child's settings lock and the parent's PIN. */
    val lock: LockUi = LockUi(),
)

/**
 * The child's settings lock and the parent's PIN (spec §10) as the screen shows them. [unlockedUntilMs]: a right PIN
 * opened a child's settings until about then (wall clock); the view model times the unlock on a monotonic clock and sets
 * it back to null when the time is up, so moving the phone's clock back never keeps the settings open. [lastCheck],
 * [pinSaved]: the outcome of the last try and of the last PIN save. [checkFailed]: the last try could not be checked.
 * Never the PIN itself.
 */
data class LockUi(
    val status: ChildLockStatus = ChildLockStatus(),
    val unlockedUntilMs: Long? = null,
    val lastCheck: PinCheck? = null,
    val pinSaved: PinSave? = null,
    val checkFailed: Boolean = false,
)

/** What the settings screen needs from core/data; a seam for tests. */
internal interface SettingsActions {
    val group: Flow<GroupState>
    val usage: Flow<UsageSettings>
    val prefs: Flow<Prefs>
    val service: Flow<ServiceStatus>

    /** This child's signed sharing state, as Overview reads it; null without a snapshot of this device. */
    val ownSharing: Flow<Sharing?> get() = flowOf(null)

    /** Re-reads permissions and usage access now (ServiceRepository.refresh). */
    fun refreshStatus() = Unit

    suspend fun setSharing(on: Boolean)

    suspend fun setRetention(detailDays: Int, summaryDays: Int)

    suspend fun setSummaryMode(mode: SummaryMode)

    suspend fun setNoContactHours(hours: Int)

    /** The «Start» button only (SyncControl.startByUser): the one thing that clears a user's Stop. */
    fun startByUser()

    fun requestStop()

    /** «Sync now»: fast mode for a minute; honours a user's Stop (SyncControl). */
    fun syncNow()

    /** LocalReset.status: app-wide, so it outlives this view model. */
    val resetStatus: Flow<ResetStatus>

    /** LocalReset.launchResetIdentity: on the app scope; joins a wipe already running. */
    fun launchReset()

    fun clearResetFailure()

    val cloud: Flow<CloudStatus> get() = flowOf(CloudStatus())

    val signIn: Flow<SignInState> get() = flowOf(SignInState.Idle)

    /** The address for the browser, or null (the sign-in state then says why). [takeOver]: taking the storage (spec §7.1, §7.2). */
    suspend fun startCloudSignIn(takeOver: Boolean = false): String? = null

    /** Spec §7.1: the owner asks [to] to take the storage; false when refused (CloudSignIn.requestHandover). */
    suspend fun requestHandover(to: DeviceId): Boolean = false

    /** Finishes on the app scope (CloudRepository.finishSignIn): leaving the screen does not cancel it. */
    fun finishCloudSignIn(input: String) = Unit

    fun dismissCloudSignIn() = Unit

    /** The sign-in page answered with an error (CloudRepository.refuseSignIn). */
    fun refuseCloudSignIn() = Unit

    /** Spec §10: the family PIN and this phone's wait (ChildLock.status). */
    val childLock: Flow<ChildLockStatus> get() = flowOf(ChildLockStatus())

    suspend fun unlock(pin: String): PinCheck = PinCheck.NO_PIN

    suspend fun setPin(pin: String): PinSave = PinSave.NOT_ALLOWED
}

private class RepositoryActions(
    group: GroupRepository,
    private val settings: SettingsRepository,
    private val serviceRepo: ServiceRepository,
    private val reset: LocalReset,
    usageRepo: UsageRepository,
    private val cloudRepo: CloudRepository,
    private val lock: ChildLock,
) : SettingsActions {
    override val group = group.state
    override val usage = settings.usageSettings
    override val prefs = settings.appPrefs
    override val service = serviceRepo.status
    override val ownSharing: Flow<Sharing?> = usageRepo.dashboard.map { it?.selfUsage?.sharing }.onStart { emit(null) }.distinctUntilChanged()

    override fun refreshStatus() = serviceRepo.refresh()

    override suspend fun setSharing(on: Boolean) = settings.setSharing(on)

    /** SettingsStore (DataStore, IO) then Pruner (Room, IO; the group file on Dispatchers.IO). */
    override suspend fun setRetention(detailDays: Int, summaryDays: Int) = settings.setRetention(detailDays, summaryDays)

    override suspend fun setSummaryMode(mode: SummaryMode) = settings.setSummaryMode(mode)

    override suspend fun setNoContactHours(hours: Int) = settings.setNoContactHours(hours)

    override fun startByUser() = serviceRepo.start()

    override fun requestStop() = serviceRepo.stop()

    override fun syncNow() = serviceRepo.syncNow()

    override val resetStatus = reset.status

    /** LocalReset: stopAndWait → forget the group files → pipeline.exclusive { wipe; settings reset; new keys }. */
    override fun launchReset() {
        reset.launchResetIdentity()
    }

    override fun clearResetFailure() = reset.clearFailure()

    override val cloud = cloudRepo.status
    override val signIn: Flow<SignInState> = cloudRepo.signInState

    override suspend fun startCloudSignIn(takeOver: Boolean): String? =
        cloudRepo.startSignIn(if (takeOver) SignInMode.TAKE_OVER else SignInMode.ENABLE)

    override suspend fun requestHandover(to: DeviceId): Boolean = cloudRepo.requestHandover(to)

    override fun finishCloudSignIn(input: String) {
        cloudRepo.finishSignIn(input)
    }

    /** Closes the dialog only: a pending sign-in stays, so an app link arriving later still completes it. */
    override fun dismissCloudSignIn() = cloudRepo.dismissSignIn()

    override fun refuseCloudSignIn() = cloudRepo.refuseSignIn()

    override val childLock = lock.status

    override suspend fun unlock(pin: String) = lock.unlock(pin)

    override suspend fun setPin(pin: String) = lock.setPin(pin)
}

/**
 * The settings screen's state and actions. Nothing here starts the sync service: opening settings never clears a
 * user's Stop, only [startSync] does. The identity reset runs in LocalReset on the app scope and its status is
 * app-wide, so leaving the screen mid-wipe neither cancels it nor loses its failure. Every failure is shown.
 */
class SettingsViewModel internal constructor(
    private val actions: SettingsActions,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Monotonic, counts deep sleep, cannot be set by the user: it alone ends an unlock. */
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    constructor(
        group: GroupRepository,
        settings: SettingsRepository,
        service: ServiceRepository,
        reset: LocalReset,
        usage: UsageRepository,
        cloud: CloudRepository,
        childLock: ChildLock,
    ) : this(RepositoryActions(group, settings, service, reset, usage, cloud, childLock))

    private val _error = MutableStateFlow<SettingsError?>(null)

    /** The last failed setting change (not the reset: that one is in LocalReset.status). */
    val error: StateFlow<SettingsError?> = _error.asStateFlow()

    private val data = combine(
        actions.group,
        actions.usage,
        actions.prefs,
        actions.service.map<ServiceStatus, ServiceStatus?> { it }.onStart { emit(null) },
        actions.ownSharing,
    ) { g, u, p, s, own -> SettingsUi(g, u, p, s, ownSharing = own) }

    private val cloudPart = combine(actions.cloud, actions.signIn) { c, s -> c to s }

    private val unlockedUntil = MutableStateFlow<Long?>(null)
    private val lastCheck = MutableStateFlow<PinCheck?>(null)
    private val pinSaved = MutableStateFlow<PinSave?>(null)
    private val checkFailed = MutableStateFlow(false)
    private var expiry: Job? = null
    private val lockPart = combine(actions.childLock, unlockedUntil, lastCheck, pinSaved, checkFailed) { s, u, c, p, f ->
        LockUi(s, u, c, p, f)
    }

    val ui: StateFlow<SettingsUi> = combine(data, _error, actions.resetStatus, cloudPart, lockPart) { d, e, r, (cloud, signIn), lock ->
        val resetFailed = r is ResetStatus.Failed && r.kind == ResetKind.NEW_IDENTITY
        d.copy(
            error = e ?: SettingsError.RESET.takeIf { resetFailed }, resetting = r is ResetStatus.Running, cloud = cloud, signIn = signIn,
            lock = lock,
        )
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

    /** «Sync now»: a minute of fast mode; it honours a user's Stop (SyncControl), so it never clears one. */
    fun syncNow() = actions.syncNow()

    /** ON_RESUME: back from Android's settings, the usage access row updates at once (U1). */
    fun refreshStatus() = actions.refreshStatus()

    fun dismissError() {
        if (_error.value != null) _error.value = null else actions.clearResetFailure()
    }

    /** On success the group state reads NoGroup and the app returns to the first run. */
    fun resetIdentity() {
        _error.value = null
        actions.launchReset()
    }

    /**
     * The switch, «Войти в Яндекс», or taking the storage ([takeOver], spec §7): [open] gets the authorize address (the
     * in-app sign-in for the verification page, else a plain ACTION_VIEW, no Custom Tabs library).
     */
    fun startCloudSignIn(takeOver: Boolean = false, open: (String) -> Unit) {
        viewModelScope.launch {
            try {
                actions.startCloudSignIn(takeOver)?.let(open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cloud sign-in failed to start: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Spec §7.1 «Владелец выбирает родителя». A refusal (the parent is no longer an admin, this phone no longer owns
     * the storage, the entry was not written) is shown like a failure: the dialog has already closed.
     */
    fun requestHandover(to: DeviceId) = attempt(SettingsError.HANDOVER, "requestHandover") {
        check(actions.requestHandover(to)) { "handover refused" }
    }

    /**
     * The pasted code, finished on the app scope (not viewModelScope, final review Minor 1); the result shows in the
     * dialog (SignInState.Done, or Waiting with an error to paste again).
     */
    fun finishCloudSignIn(code: String) = actions.finishCloudSignIn(code)

    /** Closes the dialog; a pending sign-in stays, so the app link still completes it (CloudSignIn.dismiss). */
    fun dismissCloudSignIn() = actions.dismissCloudSignIn()

    /** The user refused or cancelled on the Yandex page: the sign-in failure shows (CloudSignIn.refused). */
    fun refuseCloudSignIn() = actions.refuseCloudSignIn()

    /** A PIN typed on a child's phone; the PIN goes to ChildLock only, never into state or a log. */
    fun unlock(pin: String) {
        checkFailed.value = false
        viewModelScope.launch {
            val r = try {
                actions.unlock(pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "unlock failed: ${e.javaClass.simpleName}")
                checkFailed.value = true
                null
            }
            lastCheck.value = r
            if (r == PinCheck.OK) {
                unlockedUntil.value = clock() + UNLOCK_MS
                val end = elapsed() + UNLOCK_MS
                expiry?.cancel()
                // Rechecked every RECHECK_MS: a coroutine delay stops while the phone sleeps, elapsedRealtime does not.
                expiry = viewModelScope.launch {
                    while (elapsed() < end) delay(minOf(end - elapsed(), RECHECK_MS))
                    lock()
                }
            }
        }
    }

    /** «Закрыть настройки», or the end of [UNLOCK_MS] on the monotonic clock. */
    fun lock() {
        expiry?.cancel()
        expiry = null
        unlockedUntil.value = null
        lastCheck.value = null
        checkFailed.value = false
    }

    /** An admin parent's new family PIN (spec §10). A save that threw is said as [PinSave.FAILED] (rulings P6). */
    fun setPin(pin: String) {
        pinSaved.value = null
        viewModelScope.launch {
            pinSaved.value = try {
                actions.setPin(pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setPin failed: ${e.javaClass.simpleName}")
                PinSave.FAILED
            }
        }
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

    companion object {
        private const val TAG = "KinsightSettings"

        /** How long a right parent PIN keeps a child's settings open. */
        const val UNLOCK_MS = 5 * 60_000L

        /** How often a running unlock looks at the monotonic clock. */
        private const val RECHECK_MS = 1_000L
    }
}
