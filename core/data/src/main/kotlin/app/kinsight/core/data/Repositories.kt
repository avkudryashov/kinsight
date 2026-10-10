package app.kinsight.core.data

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.wipeAll
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * The group as the screens see it, and the admin and leave operations. [scope] is app-wide (owned by Graph): leaving
 * outlives any screen (preflight C5). [sessionsFinished] is the sync engine's finished-session counter; [forget] is
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
    private val sessionsFinished: Flow<Int>,
    private val forget: suspend () -> Unit,
    private val leaveTimeoutMs: Long = LEAVE_TIMEOUT_MS,
    private val random: (Int) -> ByteArray = Primitives::randomBytes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _leaving = MutableStateFlow(false)
    private val leaveLock = Any()
    private var leaveJob: Job? = null

    /** True from leaveAndForget until the local wipe is done (or failed). */
    val leaving: StateFlow<Boolean> = _leaving.asStateFlow()

    /** While [leaving], every state but a key or storage error reads as [GroupState.Leaving]. */
    val state: Flow<GroupState> =
        combine(snapshots.snapshots.map(SnapshotViews::groupState).onStart { emit(GroupState.Loading) }, _leaving) { s, leaving ->
            if (leaving && s !is GroupState.KeyError && s !is GroupState.StorageError) GroupState.Leaving else s
        }

    /** «Создать группу» (spec §9.4): this device becomes the first admin, a parent (spec §4.8), in a fresh database. */
    suspend fun createGroup(deviceName: String) {
        check(!withContext(io) { groups.exists() }) { "a group already exists" }
        val k = keys()
        val genesis = GroupGenesis.create(k, deviceName)
        pipeline.exclusive { db.wipeAll() }
        pipeline.appendOwn(PublishTarget(genesis.groupId, k)) { author ->
            check(author.lastSeq == 0L) { "own log not empty" }
            listOf(genesis.entry)
        }
        withContext(io) { groups.write(GroupConfig(genesis.groupId, random(32))) } // last: a crash before leaves no group
        snapshots.invalidate()
        sync.start(fast = false)
    }

    suspend fun grantAdmin(id: DeviceId) {
        pipeline.appendOwn { author -> listOf(author.grantAdminEntry(id)) }
        sync.start(fast = true)
    }

    /** RemoveMember with the removed device's contiguous seq as we know it (spec §4.5). */
    suspend fun remove(id: DeviceId) {
        val group = withContext(io) { groups.read() } ?: return
        val lastSeq = RoomEntryStore(db, group.groupId).versionVector()[id]
        pipeline.appendOwn { author -> listOf(author.removeMemberEntry(id, lastSeq)) }
        sync.start(fast = true)
    }

    /** LEFT_GROUP, then a fast sync so the family sees it (spec §9.5); [leaveAndForget] wipes afterwards. */
    suspend fun leave() {
        pipeline.appendOwn { author -> listOf(author.leaveGroupEntry()) }
        sync.start(fast = true)
    }

    /**
     * The «Leave group» button (spec §9.5, preflight C5/C6), on the app scope: sign LEFT_GROUP, start a fast sync,
     * wait until one more session has finished (bounded by [leaveTimeoutMs]), then forget the group locally. The
     * family gets LEFT_GROUP through the membership offer of that session, which then ends NOT_A_MEMBER, so the wait
     * is on the finished-session counter, not on a completed sync. A second call while leaving returns the same job.
     * A failure ends [leaving] and leaves the state as it is (Removed after a signed LEFT_GROUP: «start over»).
     */
    fun leaveAndForget(): Job = synchronized(leaveLock) {
        leaveJob?.takeIf { it.isActive }?.let { return it }
        _leaving.value = true
        scope.launch {
            try {
                coroutineScope {
                    // Subscribed before signing, so the counter's current value is the baseline.
                    val session = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(leaveTimeoutMs) { sessionsFinished.awaitIncrease() }
                    }
                    leave()
                    session.await()
                }
                forget()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing to report to: the screens see the state (Removed or Ready) once leaving is false.
            }
        }.also {
            leaveJob = it
            // Also when the scope is already cancelled and the body never runs.
            it.invokeOnCompletion { _leaving.value = false }
        }
    }

    companion object {
        const val LEAVE_TIMEOUT_MS = 60_000L

        /**
         * Returns at the first observed increase. A restarted service publishes a fresh counter from zero; a drop is
         * not a session, but the next step up from it is.
         */
        private suspend fun Flow<Int>.awaitIncrease() {
            var last: Int? = null
            first { v ->
                val up = last.let { it != null && v > it }
                last = v
                up
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
) {
    suspend fun prune() {
        val group = groups.read() ?: return
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
