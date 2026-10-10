package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.wipeAll
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.ServiceSnapshot
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncProblem
import app.kinsight.core.transport.ble.TransportStateStore
import app.kinsight.core.usage.PublishTarget
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsagePipeline
import app.kinsight.core.usage.UsageSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** The sync engine's session progress: finished sessions (any outcome; from zero after a service restart), one running. */
data class SessionProgress(val finished: Int, val syncing: Boolean)

/** Why «Leave group» did not finish; the group screen shows it (T15) and calls [GroupRepository.dismissLeaveError]. */
enum class LeaveError {
    /** No group, or this device is no longer an active member: nothing to leave. */
    NO_GROUP,

    /** The only admin while others remain: grant admin to another parent first (spec §9.5). */
    SOLE_ADMIN,

    /** LEFT_GROUP could not be signed or stored; nothing was wiped. */
    NOT_SIGNED,

    /** LEFT_GROUP is signed but the local wipe failed: the state reads Removed, «start over» retries the wipe. */
    WIPE_FAILED,
}

/**
 * The group as the screens see it, and the admin and leave operations. [scope] is app-wide (owned by Graph): leaving
 * outlives any screen (preflight C5). [sessions] is the sync engine's session progress; [forget] is
 * LocalReset.forgetGroup. GroupStore calls are fsynced file I/O and run on [io] (preflight C20).
 */
class GroupRepository(
    private val snapshots: Snapshots,
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val pipeline: UsagePipeline,
    private val sync: SyncControl,
    private val scope: CoroutineScope,
    private val sessions: Flow<SessionProgress>,
    private val forget: suspend () -> Unit,
    private val leaveTimeoutMs: Long = LEAVE_TIMEOUT_MS,
    private val random: (Int) -> ByteArray = Primitives::randomBytes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private enum class Phase { IDLE, LEAVING, LEFT }

    private val phase = MutableStateFlow(Phase.IDLE)
    private val _leaving = MutableStateFlow(false)
    private val _leaveError = MutableStateFlow<LeaveError?>(null)
    private val leaveLock = Any()
    private var leaveJob: Job? = null

    /** True from the start of a leave until the local wipe is done and the snapshot reads NoGroup, or it failed. */
    val leaving: StateFlow<Boolean> = _leaving.asStateFlow()

    /** The last leave failure, until [dismissLeaveError]. */
    val leaveError: StateFlow<LeaveError?> = _leaveError.asStateFlow()

    /**
     * While leaving, every state but a key or storage error reads as [GroupState.Leaving]. After a successful leave
     * each collector keeps reading Leaving until its own snapshot reaches NoGroup, so a stale Removed never flashes.
     */
    val state: Flow<GroupState> = flow {
        var holding = false
        combine(snapshots.snapshots.map(SnapshotViews::groupState).onStart { emit(GroupState.Loading) }, phase) { s, p -> s to p }
            .collect { (s, p) ->
                val terminal = s is GroupState.NoGroup || s is GroupState.KeyError || s is GroupState.StorageError
                holding = when {
                    terminal -> false
                    p == Phase.LEAVING -> true
                    p == Phase.LEFT -> holding // until this collector's own snapshot reaches NoGroup
                    else -> false // IDLE: no leave, or a failed one (its Removed or Ready is real)
                }
                emit(if (holding) GroupState.Leaving else s)
            }
    }

    /** «Создать группу» (spec §9.4): this device becomes the first admin, a parent (spec §4.8), in a fresh database. */
    suspend fun createGroup(deviceName: String) {
        check(!withContext(io) { groups.exists() }) { "a group already exists" }
        val k = keys()
        val genesis = GroupGenesis.create(k, deviceName)
        pipeline.exclusive { db.wipeAll() }
        check(
            pipeline.appendOwn(PublishTarget(genesis.groupId, k)) { author ->
                check(author.lastSeq == 0L) { "own log not empty" }
                listOf(genesis.entry)
            },
        ) { "genesis not stored" }
        withContext(io) { groups.write(GroupConfig(genesis.groupId, random(32))) } // last: a crash before leaves no group
        snapshots.invalidate()
        sync.start(fast = false)
    }

    /** Throws IllegalStateException without a group (nothing signed, no sync started). */
    suspend fun grantAdmin(id: DeviceId) {
        signOwn("grant admin") { author -> listOf(author.grantAdminEntry(id)) }
        sync.start(fast = true)
    }

    /** RemoveMember with the removed device's contiguous seq as we know it (spec §4.5). Throws without a group. */
    suspend fun remove(id: DeviceId) {
        val group = checkNotNull(withContext(io) { groups.read() }) { "no group: cannot remove a member" }
        val lastSeq = RoomEntryStore(db, group.groupId).versionVector()[id]
        signOwn("remove member") { author -> listOf(author.removeMemberEntry(id, lastSeq)) }
        sync.start(fast = true)
    }

    /**
     * LEFT_GROUP, then a fast sync so the family sees it (spec §9.5). Only through [leaveAndForget], which wipes.
     * Leaving is an explicit user action, so it starts the service as the user ([SyncControl.startByUser]): with
     * the user's Stop active a plain start would be honoured as stopped and LEFT_GROUP would never be sent.
     */
    internal suspend fun leave() {
        signOwn("leave") { author -> listOf(author.leaveGroupEntry()) }
        sync.startByUser()
        sync.start(fast = true)
    }

    private suspend fun signOwn(what: String, build: (EntryAuthor) -> List<DecodedEntry>) {
        if (!pipeline.appendOwn(build = build)) {
            Log.w(TAG, "$what: no group to sign in")
            throw IllegalStateException("no group: cannot $what")
        }
    }

    fun dismissLeaveError() {
        _leaveError.value = null
    }

    /**
     * The «Leave group» button (spec §9.5, preflight C5/C6), on the app scope:
     * 1. refuse ([leaveError]) without a group or for the sole admin of others ([SnapshotViews.canLeave]);
     * 2. sign LEFT_GROUP and start a fast sync; a failure here wipes nothing ([LeaveError.NOT_SIGNED]);
     * 3. right after the commit, take the session baseline and wait (bounded by [leaveTimeoutMs]) for a session that
     *    began after it: one more finished session, two if one was running at the commit. The family gets LEFT_GROUP
     *    through that session's membership offer, which then ends NOT_A_MEMBER, so this waits on any finished session;
     * 4. forget the group locally, then wait for the snapshot to read NoGroup before [leaving] ends.
     * A second call while leaving returns the same job. Every failure is logged.
     */
    fun leaveAndForget(): Job = synchronized(leaveLock) {
        leaveJob?.takeIf { it.isActive }?.let { return it }
        _leaveError.value = null
        scope.launch {
            val refusal = refusal()
            if (refusal != null) {
                Log.w(TAG, "leave refused: $refusal")
                _leaveError.value = refusal
                return@launch
            }
            setPhase(Phase.LEAVING)
            var left = false
            try {
                try {
                    leave()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "leave: LEFT_GROUP not signed", e)
                    _leaveError.value = LeaveError.NOT_SIGNED
                    return@launch
                }
                val carried = withTimeoutOrNull(leaveTimeoutMs) { sessions.awaitSessionAfterNow() }
                if (carried == null) Log.w(TAG, "leave: no session finished within $leaveTimeoutMs ms; forgetting anyway")
                try {
                    forget()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "leave: local wipe failed", e)
                    _leaveError.value = LeaveError.WIPE_FAILED
                    return@launch
                }
                withTimeoutOrNull(NO_GROUP_WAIT_MS) { snapshots.snapshots.first { it is GroupSnapshot.NoGroup } }
                left = true
            } finally {
                setPhase(if (left) Phase.LEFT else Phase.IDLE)
            }
        }.also { leaveJob = it }
    }

    private fun setPhase(p: Phase) {
        _leaving.value = p == Phase.LEAVING
        phase.value = p
    }

    private suspend fun refusal(): LeaveError? {
        val s = try {
            snapshots.load(emptyMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "leave: snapshot unreadable", e)
            return LeaveError.NO_GROUP
        }
        val ready = s as? GroupSnapshot.Ready
        return when {
            ready == null || ready.removed -> LeaveError.NO_GROUP
            !SnapshotViews.canLeave(ready) -> LeaveError.SOLE_ADMIN
            else -> null
        }
    }

    companion object {
        const val LEAVE_TIMEOUT_MS = 60_000L
        private const val NO_GROUP_WAIT_MS = 5_000L
        private const val TAG = "KinsightGroup"

        /**
         * The first value is the baseline (taken right after LEFT_GROUP committed). Counts finished sessions from
         * there: a drop (a restarted service counts from zero) is not a session, the next step up is. A session
         * running at the baseline may have begun before the commit, so then two are needed.
         */
        private suspend fun Flow<SessionProgress>.awaitSessionAfterNow() {
            var last: Int? = null
            var needed = 1
            var counted = 0
            first { p ->
                val prev = last
                if (prev == null) {
                    if (p.syncing) needed = 2
                } else if (p.finished > prev) {
                    counted += p.finished - prev
                }
                last = p.finished
                counted >= needed
            }
        }
    }
}

class UsageRepository(
    private val snapshots: Snapshots,
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val dashboard: Flow<DashboardState?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }?.let { SnapshotViews.dashboard(it, today(), clock()) }
    }

    fun detail(id: DeviceId, period: Period): Flow<DeviceDetail?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.let { SnapshotViews.detail(it, id, period, today(), clock()) }
    }
}

data class ServiceStatus(
    val state: ServiceState,
    val stoppedByUser: Boolean,
    val lastSyncMs: Long?,
    val missingPermissions: List<String>,
    val syncing: Boolean,
    val problem: SyncProblem?,
    /** The engine's finished-session counter (any outcome); starts over from zero when the service restarts. */
    val finishedSessions: Int = 0,
)

class ServiceRepository(
    private val snapshot: StateFlow<ServiceSnapshot>,
    private val transport: TransportStateStore,
    private val missingPermissions: () -> List<String>,
    private val control: SyncControl,
) {
    /** Re-read every 30 s as well: permissions change in Settings without telling us. */
    private val ticks = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    val status: Flow<ServiceStatus> = combine(snapshot, transport.stoppedByUserFlow, ticks) { s, stopped, _ ->
        ServiceStatus(
            state = s.state,
            stoppedByUser = stopped,
            lastSyncMs = s.sync.lastSyncWallMs ?: s.lastSyncWallMs,
            missingPermissions = missingPermissions(),
            syncing = s.sync.syncing,
            problem = s.sync.lastProblem,
            finishedSessions = s.sync.finishedSessions,
        )
    }

    fun start() = control.startByUser()

    fun stop() = control.requestStop()

    fun syncNow() = control.start(fast = true)
}

/** Applies the retention settings to the local log now (spec §4.7). */
class Pruner(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val settings: SettingsStore,
    private val keys: suspend () -> DeviceKeys,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun prune() {
        val group = withContext(io) { groups.read() } ?: return
        val s = settings.current()
        val policy = SyncPolicy(
            self = keys().deviceId,
            detailRetentionDays = s.detailRetentionDays.toLong(),
            summaryRetentionDays = s.summaryRetentionDays.toLong(),
        )
        RoomEntryStore(db, group.groupId).prune(policy)
    }
}

class SettingsRepository(
    private val settings: SettingsStore,
    private val prefs: AppPrefs,
    private val pipeline: UsagePipeline,
    private val prune: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val usageSettings: Flow<UsageSettings> = settings.settings
    val appPrefs: Flow<Prefs> = prefs.prefs

    /** «Согласен» on the consent screen, then sharing on (a child only; the pipeline refuses otherwise). */
    suspend fun consentAndShare() {
        settings.recordConsent(clock())
        pipeline.setSharing(true)
    }

    suspend fun setSharing(on: Boolean) = pipeline.setSharing(on)

    suspend fun setRetention(detailDays: Int, summaryDays: Int) {
        settings.setRetention(detailDays, summaryDays)
        prune()
    }

    suspend fun setSummaryMode(mode: SummaryMode) = prefs.setSummaryMode(mode)

    suspend fun setNoContactHours(hours: Int) = prefs.setNoContactHours(hours)
}
