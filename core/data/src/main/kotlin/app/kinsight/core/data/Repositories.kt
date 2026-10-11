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
import app.kinsight.core.sync.CategoryOverrideRules
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.model.proto.CategoryOverride
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** The sync engine's session progress: finished sessions (any outcome; from zero after a service restart), one running. */
data class SessionProgress(val finished: Int, val syncing: Boolean)

/** Why «Leave group» did not finish; the group screen shows it and calls [GroupRepository.dismissLeaveError]. */
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
 * outlives any screen. [sessions] is the sync engine's session progress; [forget] is
 * LocalReset.forgetGroup. GroupStore calls are fsynced file I/O and run on [io].
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
     * The «Leave group» button (spec §9.5), on the app scope:
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

/**
 * The screens' usage reads. Every flow derives from the one shared [Snapshots.snapshots] (one Room load per change for
 * all of them); only [detail] and [app] add their own range query of UsageDetail entries.
 */
class UsageRepository(
    private val snapshots: Snapshots,
    private val prefs: AppPrefs,
    private val appendOwn: OwnAppend,
    private val date: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The «no contact» window, so a card says «collection off» only past it (U1); the default when unreadable. */
    private val noContactMs = prefs.prefs.map { it.noContactHours * 3_600_000L }
        .catch { emit(Prefs().noContactHours * 3_600_000L) }
        .distinctUntilChanged()

    /**
     * The dashboard (spec §9.2). Each shown child's UsageDetail of today-7..today is read with every snapshot, so a card
     * compares today with «usual by this time» (U2): the 7 days before the child's today, which is the viewer's today
     * or, for a child already on its next day, tomorrow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboard: Flow<DashboardState?> = combine(snapshots.snapshots, noContactMs, ::Pair).mapLatest { (s, noContact) ->
        val ready = (s as? GroupSnapshot.Ready)?.takeUnless { it.removed } ?: return@mapLatest null
        val today = today()
        val shown = if (ready.membership.active[ready.self]?.isChild == true) {
            listOf(ready.self)
        } else {
            ready.membership.active.values.filter { it.isChild }.map { it.id }
        }
        val details = shown.associateWith { snapshots.details(ready.groupId, it, today.minusDays(7), today) }
        SnapshotViews.dashboard(ready, today, clock(), noContact, details)
    }.distinctUntilChanged()

    /** The viewer's local date, the bound of forward navigation; screens anchor their first range on it. */
    fun today(): LocalDate = date()

    /**
     * The family's shared category overrides (spec §4.4, stage 5c): derived from the signed CategoryOverride entries in
     * the shared snapshot ([CategoryOverrides], active parents only). Empty without a group or when removed.
     */
    val categoryOverrides: Flow<Map<String, AppCategory>> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.let(CategoryOverrides::effective).orEmpty()
    }.distinctUntilChanged()

    /** The parent whose entry sets [packageName]'s effective category; null when no override applies. */
    fun categoryAuthor(packageName: String): Flow<DeviceId?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }
            ?.let { CategoryOverrides.effectiveWithAuthors(it.categoryOverrides, it.membership)[packageName]?.author }
    }.distinctUntilChanged()

    /**
     * The name of the other parent whose entry sets [packageName]'s effective category; null for the viewer's own choice
     * or no override. Only active parents' entries take effect (spec §4.4), so the author is always an active member.
     * A blank name comes back as "" (the screen then says «another parent»).
     */
    fun categoryChangedBy(packageName: String): Flow<String?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }?.let {
            val author = CategoryOverrides.effectiveWithAuthors(it.categoryOverrides, it.membership)[packageName]?.author
            author?.takeIf { a -> a != it.self }?.let { a -> it.membership.active[a]?.displayName?.takeUnless(String::isBlank) ?: "" }
        }
    }.distinctUntilChanged()

    /**
     * Serialises [setCategory] and [migrateLocalCategories] (each a read of the effective map, then an append). Lock
     * order: this, then the pipeline lock inside [appendOwn]; neither the control nor the state lock is taken here.
     */
    private val categoryLock = Mutex()

    /**
     * Signs a CategoryOverride entry in the own log; [category] null returns the app to its own (catalog or built-in)
     * category for every parent. Refused (logged, nothing signed) without a group, on a child's phone, for a removed
     * device and for a package or category receivers would reject (CategoryOverrideRules: receivers would skip it, not
     * counted, never applied). Choosing what the winner already holds (same wire value) signs nothing; a winner with
     * a category from a newer app reads as the default here, but choosing the default still signs a clear. Runs the local-prefs
     * migration first, so an old local choice can never undo a newer one.
     */
    suspend fun setCategory(packageName: String, category: AppCategory?): Unit = categoryLock.withLock {
        migrateLocked()
        val wire = CategoryOverrides.wire(category)
        if (!CategoryOverrideRules.packageOk(packageName) || wire !in 0..CategoryOverrideRules.MAX_CATEGORY) {
            Log.w(TAG, "category override refused: invalid package or category")
            return@withLock
        }
        val ready = parentSnapshot() ?: run {
            Log.w(TAG, "category override refused: not a parent of a group")
            return@withLock
        }
        val winners = CategoryOverrides.winners(ready.categoryOverrides, ready.membership)
        // Wire values, not AppCategory: a newer app's category reads as null here and must not equal "default".
        if ((winners[packageName]?.body?.category_override?.category ?: 0) == wire) return@withLock
        val rank = CategoryOverrides.nextRank(winners[packageName])
        if (!appendOwn { a -> listOf(a.categoryOverrideEntry(packageName, wire, rank)) }) Log.w(TAG, "category override: no group to sign in")
    }

    /**
     * One-time move of the stage 5a local overrides into the shared log (spec §4.4 «Переход»), run at app start on a
     * parent's phone. A local choice is published only for an app no active parent has set or cleared yet (no
     * CategoryOverride entry from any of them): a shared decision, a deliberate clear included, is never overridden by
     * an old local one. Valid choices are signed in one append, then the prefs are cleared. Crash-safe: prefs are
     * cleared only after the append committed; a crash in between leaves prefs whose choices are now own entries, so
     * the rerun signs nothing and just clears. Without a group, on a child's phone or when the append fails, nothing
     * is cleared.
     */
    suspend fun migrateLocalCategories(): Unit = categoryLock.withLock { migrateLocked() }

    private suspend fun migrateLocked() {
        val local = prefs.categoryOverrides.first()
        if (local.isEmpty()) return
        val ready = parentSnapshot() ?: return
        val winners = CategoryOverrides.winners(ready.categoryOverrides, ready.membership)
        val own = ready.categoryOverrides.filter { it.author == ready.self }.associateBy { it.body.category_override!!.package_name }
        val publish = local.filter { (pkg, c) ->
            val wire = CategoryOverrides.wire(c)
            CategoryOverrideRules.packageOk(pkg) && wire in 1..CategoryOverrideRules.MAX_CATEGORY &&
                winners[pkg] == null && own[pkg]?.body?.category_override?.category != wire
        }
        if (publish.isNotEmpty()) {
            // No winner to order after: every published package has no entry from an active parent (rank 0 + 1).
            val rank = CategoryOverrides.nextRank(null)
            val ok = appendOwn { a ->
                publish.entries.sortedBy { it.key }.map { (pkg, c) -> a.categoryOverrideEntry(pkg, CategoryOverrides.wire(c), rank) }
            }
            if (!ok) return
            Log.i(TAG, "published ${publish.size} local category overrides")
        }
        prefs.clearCategoryOverrides()
    }

    /** A fresh read (not the shared flow, which may lag a just-committed append) when this phone is an active parent. */
    private suspend fun parentSnapshot(): GroupSnapshot.Ready? {
        val ready = snapshots.load(emptyMap()) as? GroupSnapshot.Ready ?: return null
        return ready.takeIf { ready.membership.active[ready.self]?.isChild == false }
    }

    /**
     * The parent's daily limits (spec §9.7, stage 5b) of devices that are children of the current group; rules of a
     * device that left, was removed or is not a child are kept in [AppPrefs] but not listed. Empty without a group.
     */
    val thresholds: Flow<List<ThresholdRule>> = combine(snapshots.snapshots, prefs.thresholds) { s, rules ->
        val ready = (s as? GroupSnapshot.Ready)?.takeUnless { it.removed } ?: return@combine emptyList()
        rules.filter { ready.membership.active[it.child]?.isChild == true }
    }.distinctUntilChanged()

    /** Whether this phone is a parent of the current group: only a parent sets thresholds (hidden on a child's phone). */
    val viewerIsParent: Flow<Boolean> = snapshots.snapshots.map { s ->
        val ready = (s as? GroupSnapshot.Ready)?.takeUnless { it.removed }
        ready?.membership?.active?.get(ready.self)?.isChild == false
    }.distinctUntilChanged()

    /** Adds or replaces the rule of [rule]'s (child, target); build it with [ThresholdRule.of]. */
    suspend fun setThreshold(rule: ThresholdRule) = prefs.setThreshold(rule)

    suspend fun removeThreshold(id: String) = prefs.removeThreshold(id)

    /**
     * [range] of device [id], reloaded on every log change; the device's UsageDetail entries of the range are read with
     * each snapshot for level 4 (an equal state is not re-emitted). [weekFields]: the same as the range's, it orders the
     * heatmap's weekday rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun detail(id: DeviceId, range: DetailRange, weekFields: WeekFields = WeekFields.of(Locale.getDefault())): Flow<DeviceDetail?> =
        snapshots.snapshots.mapLatest { s ->
            (s as? GroupSnapshot.Ready)?.let {
                // Overrides from the same snapshot: never paired with another snapshot's map.
                val o = CategoryOverrides.effective(it)
                val details = snapshots.details(it.groupId, id, range)
                SnapshotViews.detail(it, id, range, today(), clock(), overrides = o, details = details, weekFields = weekFields)
            }
        }.distinctUntilChanged()

    /** Every app of device [id] over [range] (the «All apps» screen), reloaded on every log change; no UsageDetail read. */
    fun apps(id: DeviceId, range: DetailRange): Flow<AppList?> = snapshots.snapshots.map { s ->
        (s as? GroupSnapshot.Ready)?.let { SnapshotViews.apps(it, id, range, today(), clock()) }
    }.distinctUntilChanged()

    /** App [packageName] of device [id] over [range], reloaded on every log or category change (the app screen); an equal state is not re-emitted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun app(id: DeviceId, packageName: String, range: DetailRange): Flow<AppDetail?> =
        snapshots.snapshots.mapLatest { s ->
            (s as? GroupSnapshot.Ready)?.let {
                val o = CategoryOverrides.effective(it)
                // Hours are drawn for a DAY only; a week or month needs no UsageDetail.
                val details = if (range.period == Period.DAY) snapshots.details(it.groupId, id, range) else emptyList()
                SnapshotViews.app(it, id, packageName, range, today(), clock(), overrides = o, details = details)
            }
        }.distinctUntilChanged()

    private companion object {
        const val TAG = "KinsightUsage"
    }
}

/**
 * A CategoryOverride with [rank] (CategoryOverrides.nextRank over the package's current winner), which orders it after
 * the choice it replaces. Its Lamport is EntryAuthor's own (membership-only observed rule): other parents' overrides
 * never move this author's clock.
 */
private fun EntryAuthor.categoryOverrideEntry(packageName: String, wire: Int, rank: Long): DecodedEntry = create {
    it.copy(category_override = CategoryOverride(package_name = packageName, category = wire, rank = rank))
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
    /** This child's sync notification is hidden: usage is paused until it is shown again (S-M3). */
    val notificationHidden: Boolean = false,
    /** Usage access (AppOpsManager GET_USAGE_STATS) is granted to this app; re-read with the permissions (U1). */
    val usageAccessGranted: Boolean = true,
)

class ServiceRepository(
    private val snapshot: StateFlow<ServiceSnapshot>,
    private val transport: TransportStateStore,
    private val missingPermissions: () -> List<String>,
    private val control: SyncControl,
    /** Whether usage access is granted (AppOpsUsageAccess.granted); read on every tick like [missingPermissions]. */
    private val usageAccess: () -> Boolean = { true },
) {
    /** [refresh]: a screen came back to the foreground, maybe from Android's settings (U1). */
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Re-read every 30 s and on [refresh] as well: permissions change in Settings without telling us. */
    private val ticks = merge(
        flow {
            while (true) {
                emit(Unit)
                delay(30_000)
            }
        },
        refreshes,
    )

    val status: Flow<ServiceStatus> =
        combine(snapshot, transport.stoppedByUserFlow, transport.notificationHiddenFlow, ticks) { s, stopped, hidden, _ ->
            ServiceStatus(
                state = s.state,
                stoppedByUser = stopped,
                lastSyncMs = s.sync.lastSyncWallMs ?: s.lastSyncWallMs,
                missingPermissions = missingPermissions(),
                syncing = s.sync.syncing,
                problem = s.sync.lastProblem,
                finishedSessions = s.sync.finishedSessions,
                notificationHidden = hidden,
                usageAccessGranted = usageAccess(),
            )
        }

    fun start() = control.startByUser()

    fun stop() = control.requestStop()

    fun syncNow() = control.start(fast = true)

    /** Re-reads the permissions and usage access now, e.g. on ON_RESUME after the user granted one in Settings. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }
}

/** Applies the retention settings to the local log now (spec §4.7), and prunes old events and catalog (A-6). */
class Pruner(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val settings: SettingsStore,
    private val keys: suspend () -> DeviceKeys,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun prune() {
        val group = withContext(io) { groups.read() } ?: return
        val s = settings.current()
        val policy = SyncPolicy(
            self = keys().deviceId,
            detailRetentionDays = s.detailRetentionDays.toLong(),
            summaryRetentionDays = s.summaryRetentionDays.toLong(),
        )
        val store = RoomEntryStore(db, group.groupId)
        store.prune(policy)
        store.pruneHistory(policy.summaryRetentionDays, clock(), policy.self)
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

    /** The child's acknowledged parents for the Overview banner (U7). */
    val acknowledgedParents: Flow<Set<DeviceId>?> = prefs.acknowledgedParents

    /** Adds [ids] (the parents the banner showed) to the acknowledged ones. */
    suspend fun acknowledgeParents(ids: Set<DeviceId>) = prefs.addAcknowledgedParents(ids)

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
