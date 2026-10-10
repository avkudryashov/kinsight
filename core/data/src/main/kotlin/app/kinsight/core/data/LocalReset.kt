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
 * 3. under the usage pipeline lock: database tables, usage settings; for a new identity also the keys
 *    ([wipeIdentity], blocking, on [io], after the data). Nothing in there takes the transport state lock.
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
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val jobLock = Any()
    private var job: Job? = null
    private val _status = MutableStateFlow<ResetStatus>(ResetStatus.Idle)

    val status: StateFlow<ResetStatus> = _status.asStateFlow()

    /** Leaving or being removed: no trace of the group; the identity and the device name stay. */
    suspend fun forgetGroup(): Unit = tracked(ResetKind.FORGET_GROUP) {
        sync.stopAndWait()
        forgetGroupFiles()
        pipeline.exclusive { wipeData() }
        prefs.resetForNewGroup()
        snapshots.invalidate()
    }

    /** «Сбросить идентичность и пересопрячь»: a new device as far as the group is concerned. */
    suspend fun resetIdentity(): Unit = tracked(ResetKind.NEW_IDENTITY) {
        sync.stopAndWait()
        forgetGroupFiles()
        pipeline.exclusive {
            wipeData()
            withContext(io) { wipeIdentity() }
        }
        prefs.reset()
        snapshots.invalidate()
    }

    /** [resetIdentity] on the app scope; joins a wipe already running (of either kind). Failures go to [status]. */
    fun launchResetIdentity(): Job = launchOnce { resetIdentity() }

    /** [forgetGroup] on the app scope («start over» after removal); joins a wipe already running. */
    fun launchForgetGroup(): Job = launchOnce { forgetGroup() }

    /** The user has seen the failure: back to [ResetStatus.Idle] (a running wipe is left alone). */
    fun clearFailure() {
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
        _status.value = ResetStatus.Running(kind)
        try {
            block()
            _status.value = ResetStatus.Idle
        } catch (e: CancellationException) {
            _status.value = ResetStatus.Failed(kind, "cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "local wipe ($kind) failed", e)
            _status.value = ResetStatus.Failed(kind, e.message ?: e::class.java.simpleName)
            throw e
        }
    }

    private suspend fun wipeData() {
        db.wipeAll()
        settings.reset()
    }

    private companion object {
        const val TAG = "KinsightReset"
    }
}
