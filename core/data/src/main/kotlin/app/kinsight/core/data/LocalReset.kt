package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.wipeAll
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsagePipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Which local wipe: [FORGET_GROUP] keeps the identity, [NEW_IDENTITY] also replaces the keys. */
enum class ResetKind { FORGET_GROUP, NEW_IDENTITY }

/** The app-wide state of the local wipes; it lives in [LocalReset] (one per process), so it outlives any screen. */
sealed interface ResetStatus {
    data object Idle : ResetStatus

    data class Running(val kind: ResetKind) : ResetStatus

    /** The last wipe failed part way ([reason]: the exception message); stays until [LocalReset.clearFailure] or a new wipe. */
    data class Failed(val kind: ResetKind, val reason: String) : ResetStatus
}

/**
 * Local wipes (spec §6.2, §9.5). Order, by the lock order controlLock → stateLock → pipeline lock:
 * 1. the sync service stops (so no session writes into the wiped database);
 * 2. [forgetGroupFiles] (in the app SyncGraph.forgetGroup: group file and transport state under the transport state
 *    lock), outside the pipeline lock: once the group file is gone the pipeline has no target and publishes nothing;
 * 3. under the usage pipeline lock: for a new identity first the keys ([wipeIdentity], blocking, on [io]), then
 *    database tables and usage settings. A [marker] brackets the identity reset; [finishInterruptedReset] runs a
 *    cut-short one again at the next start (audit A-4). Nothing in there takes the transport state lock.
 * Steps 2 and 3 hold the cloud [runLock] (taken before the other locks; a CloudRunner takes it before the pipeline lock
 * too): a cloud run waits for the whole wipe and then finds no group, so no key or token of the old group comes back.
 *
 * Wipes run one at a time (a [Mutex]) and report [status]. The screens start them with [launchResetIdentity] /
 * [launchForgetGroup] on [scope] (the app scope): a call while one runs returns the running job instead of starting
 * another, and the outcome stays in [status] after the screen is gone. The suspending calls still throw on failure.
 */
class LocalReset(
    private val db: KinsightDatabase,
    private val pipeline: UsagePipeline,
    private val forgetGroupFiles: suspend () -> Unit,
    private val settings: SettingsStore,
    private val prefs: AppPrefs,
    private val sync: SyncControl,
    private val wipeIdentity: () -> Unit,
    private val snapshots: Snapshots,
    private val marker: ResetMarker,
    /** The process-wide cloud run lock (CloudSecrets.runLock), held across the group files and the data wipe. */
    private val runLock: Mutex = Mutex(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val jobLock = Any()
    private var job: Job? = null

    @Volatile
    private var retryOnly = false
    private val _status = MutableStateFlow<ResetStatus>(ResetStatus.Idle)

    val status: StateFlow<ResetStatus> = _status.asStateFlow()

    /** A join's fresh database: no trace of the group; the identity and the device name stay. */
    suspend fun forgetGroup(): Unit = tracked(ResetKind.FORGET_GROUP) {
        sync.stopAndWait()
        // A cloud run waits for the whole wipe, then finds no group (CloudRunner reads it under the same lock).
        runLock.withLock {
            forgetGroupFiles()
            pipeline.exclusive { wipeData() }
        }
        prefs.resetForNewGroup()
        snapshots.invalidate()
    }

    /**
     * «Отключить этот телефон и подключить заново»: a new device as far as the group is concerned. It is also the end
     * of leaving the group and of a removal: the old DeviceId stays departed for good, so only new keys can join again
     * (spec §9.5). [keepName]: the device name the user typed stays (leaving, removal, the join retry); a reset that
     * [finishInterruptedReset] resumes after a crash forgets it.
     */
    suspend fun resetIdentity(keepName: Boolean = false) {
        val wasPending = withContext(io) { marker.exists() }
        tracked(ResetKind.NEW_IDENTITY) {
            // A retry queued behind the start-up recovery finds the marker gone: that reset is done, not run again.
            if (wasPending && !withContext(io) { marker.exists() }) return@tracked
            identityReset(keepName)
        }
    }

    /** True while an identity reset is cut short and must run again (the marker is on disk). Blocking file check on [io]. */
    suspend fun resetPending(): Boolean = withContext(io) { marker.exists() }

    /** False for a cut-short identity reset: the only way out of it is a retry (audit A-4). Safe on the main thread. */
    val dismissible: Boolean get() = !retryOnly

    private suspend fun identityReset(keepName: Boolean = false) {
        withContext(io) { marker.set() }
        sync.stopAndWait()
        runLock.withLock {
            forgetGroupFiles()
            pipeline.exclusive {
                // Keys before data: a crash in between leaves a new identity over stale data (wiped at the next start or
                // by the next join), never the old keys over an emptied log.
                withContext(io) { wipeIdentity() }
                wipeData()
            }
        }
        if (keepName) prefs.resetForNewGroup() else prefs.reset()
        snapshots.invalidate()
        withContext(io) { marker.clear() }
    }

    /** At process start: an identity reset that was cut short (crash, kill) runs again from the start. */
    suspend fun finishInterruptedReset(): Boolean {
        if (!withContext(io) { marker.exists() }) return false
        var ran = false
        tracked(ResetKind.NEW_IDENTITY) {
            // Re-checked under the mutex: a retry that ran meanwhile has already finished it.
            if (withContext(io) { marker.exists() }) {
                identityReset()
                ran = true
            }
        }
        return ran
    }

    /** [resetIdentity] on the app scope; joins a wipe already running (of either kind). Failures go to [status]. */
    fun launchResetIdentity(keepName: Boolean = false): Job = launchOnce { resetIdentity(keepName) }

    /** [forgetGroup] on the app scope (the retry of a failed one); joins a wipe already running. */
    fun launchForgetGroup(): Job = launchOnce { forgetGroup() }

    /** The user has seen the failure: back to [ResetStatus.Idle] (a running wipe is left alone). */
    fun clearFailure() {
        // A cut-short identity reset cannot be dismissed (audit A-4): only a retry leads out of it.
        if (retryOnly) return
        _status.update { if (it is ResetStatus.Failed) ResetStatus.Idle else it }
    }

    private fun launchOnce(block: suspend () -> Unit): Job = synchronized(jobLock) {
        job?.takeIf { it.isActive }?.let { return it }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Already logged and in status.
            }
        }.also { job = it }
    }

        private suspend fun tracked(kind: ResetKind, block: suspend () -> Unit): Unit = mutex.withLock {
        retryOnly = false
        _status.value = ResetStatus.Running(kind)
        try {
            block()
            _status.value = ResetStatus.Idle
        } catch (e: CancellationException) {
            retryOnly = markerPendingAfterFailure(kind)
            _status.value = ResetStatus.Failed(kind, "cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "local wipe ($kind) failed", e)
            retryOnly = markerPendingAfterFailure(kind)
            _status.value = ResetStatus.Failed(kind, e.message ?: e::class.java.simpleName)
            throw e
        }
    }

    private suspend fun markerPendingAfterFailure(kind: ResetKind): Boolean =
        kind == ResetKind.NEW_IDENTITY && withContext(NonCancellable + io) { runCatching { marker.exists() }.getOrDefault(true) }

    private suspend fun wipeData() {
        db.wipeAll()
        settings.reset()
    }

    private companion object {
        const val TAG = "KinsightReset"
    }
}
