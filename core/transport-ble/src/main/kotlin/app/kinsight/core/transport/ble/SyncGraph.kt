package app.kinsight.core.transport.ble

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.usage.PublishTarget
import app.kinsight.core.usage.UsageGraph
import app.kinsight.core.usage.ownUsageAllowed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide wiring of the transport until Hilt (stage 4), next to UsageGraph: the group file, the transport state,
 * and the providers the service and the usage pipeline need. Everything goes through the process singletons:
 * KinsightDatabaseFactory.shared, UsageGraph.pipeline, DeviceIdentity.
 */
object SyncGraph {
    private const val TAG = "KinsightSync"

    @Volatile
    private var groups: GroupStore? = null

    @Volatile
    private var state: TransportStateStore? = null

    /**
     * Serialises the read-modify-write sequences on the transport state (Heartbeats.emitIfDue, BluetoothOffTracker):
     * the engine's prepare and the service both reach them, and each one suspends between its read and its write.
     * Lock order: this, then the usage pipeline lock (inside append/queueEvent); nothing takes them the other way.
     */
    private val stateLock = Mutex()

    private val _snapshot = MutableStateFlow(ServiceSnapshot())

    /** The service's latest state, for the diagnostics screen. */
    val snapshot: StateFlow<ServiceSnapshot> = _snapshot.asStateFlow()

    /**
     * Whether the first-run screens are finished (AppPrefs.onboardingDone, set by the app: core/data sits above this
     * module). Until then a hidden sync notification is the onboarding grace, not hiding (NotificationWatch).
     */
    @Volatile
    var onboardingDone: suspend (Context) -> Boolean = { true }

    /** Runs after every finished session, outside any lock (the app schedules its alerts there). Must not throw. */
    @Volatile
    var onSessionFinished: (Context) -> Unit = {}

    internal fun publish(s: ServiceSnapshot) {
        _snapshot.value = s
    }

    /**
     * [snapshot] with the notification fields read now: the user can block notifications or the channel at any
     * moment without the service hearing of it. For screens that show the state (diagnostics).
     */
    fun snapshotNow(context: Context): ServiceSnapshot {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java)
        return _snapshot.value.copy(
            notificationsEnabled = nm.areNotificationsEnabled(),
            channelImportance = nm.getNotificationChannel(SyncService.CHANNEL_ID)?.importance,
        )
    }

    /** From Application.onCreate: usage entries get signed once a group exists, and only a consenting CHILD collects. */
    fun install(context: Context) {
        val app = context.applicationContext
        UsageGraph.targetProvider = { target(app) }
        UsageGraph.ownUsageGate = { t -> t != null && allowsOwnUsage(t, app) }
    }

    /**
     * Inside the pipeline lock (the gate runs there): reads Room and the settings DataStore only, never the pipeline.
     * The rule itself is [app.kinsight.core.usage.ownUsageAllowed], shared with the test devices.
     */
    private suspend fun allowsOwnUsage(t: PublishTarget, context: Context): Boolean =
        ownUsageAllowed(membership(context, t), t.keys.deviceId, UsageGraph.settings(context).current())

    private suspend fun membership(context: Context, t: PublishTarget): MembershipState? =
        Membership.compute(t.groupId, RoomEntryStore(KinsightDatabaseFactory.shared(context), t.groupId).membershipEntries())

    /**
     * For the integrity trackers: the own-usage gate by the rule queueEvent applies ([ownUsageAllowed]). Closed only
     * when that is certain (no group file, not an active consenting CHILD); an unreadable group file or identity is
     * [GateState.Unknown], so a passing failure never clears an open interval. Reads Room and DataStore, never the
     * pipeline.
     */
    private suspend fun gateState(context: Context): GateState {
        val group = try {
            groups(context).read()
        } catch (e: GroupStoreException) {
            Log.w(TAG, "group file unreadable for the integrity gate", e)
            return GateState.Unknown
        } ?: return GateState.Closed
        val keys = try {
            DeviceIdentity.get(context).keys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no identity for the integrity gate", e)
            return GateState.Unknown
        }
        val t = PublishTarget(group.groupId, keys)
        val settings = UsageGraph.settings(context).current()
        val consent = settings.consentAtMs
        return if (consent != null && ownUsageAllowed(membership(context, t), keys.deviceId, settings)) GateState.Open(consent) else GateState.Closed
    }

    /** The one GroupStore of the process (its read cache must see every write), in noBackupFilesDir. */
    fun groups(context: Context): GroupStore = groups ?: synchronized(this) {
        groups ?: GroupStore(File(context.applicationContext.noBackupFilesDir, GroupStore.FILE_NAME)).also { groups = it }
    }

    /** The one TransportStateStore of the process (DataStore allows one active instance per file). */
    fun state(context: Context): TransportStateStore = state ?: synchronized(this) {
        state ?: TransportStateStore.create(
            context.applicationContext.preferencesDataStoreFile(TransportStateStore.NAME),
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ).also { state = it }
    }

    /**
     * Null without a group or without a usable identity (the service shows why); never throws. Runs inside the usage
     * pipeline lock (UsagePipeline calls it), so it must never call back into the pipeline.
     */
    suspend fun target(context: Context): PublishTarget? {
        val group = readGroupOrNull(context) ?: return null
        val keys = try {
            DeviceIdentity.get(context).keys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no identity for the publish target", e)
            return null
        }
        return PublishTarget(group.groupId, keys)
    }

    suspend fun syncContext(context: Context): SyncContext? {
        val group = readGroupOrNull(context) ?: return null
        val keys = target(context)?.keys ?: return null
        val settings = UsageGraph.pipeline(context).currentSettings()
        val store = RoomEntryStore(KinsightDatabaseFactory.shared(context), group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries())
        return SyncContext(
            groupId = group.groupId,
            discoveryKey = group.discoveryKey,
            keys = keys,
            store = store,
            policy = SyncPolicies.forDevice(membership, keys.deviceId, settings),
        )
    }

    /** Before a session: fresh own usage (spec §7, force), the daily Heartbeat, the notification check, a refused stop interval. Never inside the pipeline lock. */
    suspend fun prepare(context: Context) {
        UsageGraph.pipeline(context).collectAndPublish(force = true)
        emitHeartbeatIfDue(context)
        onNotificationVisibility(context, notificationVisibleNow(context))
        retryStopInterval(context)
    }

    /** A SYNC_STOPPED that was refused at the start from the app: tried again before every session (fix round 2). */
    suspend fun retryStopInterval(context: Context) = stateLock.withLock {
        if (groups(context).exists()) StopTracker(state(context), recorder(context), gate(context)).retryPending()
    }

    /** Unserialised: callers go through [emitHeartbeatIfDue]. */
    private fun heartbeats(context: Context): Heartbeats {
        val app = context.applicationContext
        return Heartbeats(state(app), appVersion(app), append = { build -> UsageGraph.pipeline(app).appendOwn(build = build) })
    }

    /** Unserialised: callers go through [onBluetoothOff] and [onBluetoothOn]. */
    private fun bluetoothOff(context: Context): BluetoothOffTracker {
        val app = context.applicationContext
        return BluetoothOffTracker(state(app), record = { from, to ->
            UsageGraph.pipeline(app).queueEvent(StateEvent.Kind.BLUETOOTH_OFF, from, to)
        })
    }

    /** [heartbeats] emitIfDue under [stateLock]. */
    suspend fun emitHeartbeatIfDue(context: Context): Boolean = stateLock.withLock { heartbeats(context).emitIfDue() }

    /** Adapter went off; only while a group exists (without one nothing could ever deliver the event). */
    suspend fun onBluetoothOff(context: Context) = stateLock.withLock {
        if (groups(context).exists()) bluetoothOff(context).onOff()
    }

    /** Adapter is on again: records a pending off interval, if any. */
    suspend fun onBluetoothOn(context: Context) = stateLock.withLock {
        if (groups(context).exists()) bluetoothOff(context).onOn()
    }

    private fun recorder(context: Context): EventRecorder {
        val app = context.applicationContext
        return { kind, from, to -> UsageGraph.pipeline(app).queueEvent(kind, from, to) }
    }

    private fun gate(context: Context): GateOpenSince {
        val app = context.applicationContext
        return { gateState(app) }
    }

    /** Stop from the notification or the app (SyncService.stopByUser); without a group only the flag is kept. */
    suspend fun onUserStop(context: Context) = stateLock.withLock {
        if (groups(context).exists()) {
            StopTracker(state(context), recorder(context), gate(context)).onUserStop()
        } else {
            state(context).markStoppedByUser(atMs = null)
        }
    }

    /** A start from the app's own screen: clears the Stop and records SYNC_STOPPED for the stopped interval. */
    suspend fun onUserStart(context: Context) = stateLock.withLock {
        if (groups(context).exists()) StopTracker(state(context), recorder(context), gate(context)).onUserStart() else state(context).clearStop()
    }

    /** Only while a group exists (otherwise nothing could deliver the event); parents drop it in queueEvent. */
    suspend fun onNotificationVisibility(context: Context, visible: Boolean) = stateLock.withLock {
        if (groups(context).exists()) {
            val app = context.applicationContext
            NotificationWatch(state(app), recorder(app), gate(app), onboardingDone = { onboardingDone(app) }).onVisibility(visible)
        }
    }

    fun notificationVisibleNow(context: Context): Boolean = StatusText.notificationVisible(snapshotNow(context)) ?: true

    /** «Nearby devices» granted or not, only while a group exists: app start, every service check, hourly alerts run. */
    suspend fun onNearbyPermission(context: Context, granted: Boolean) = stateLock.withLock {
        if (groups(context).exists()) NearbyWatch(state(context), recorder(context), gate(context)).onPermission(granted)
    }

    /**
     * At process start (Application), with or without a group: without one (or before consent) the exits are only
     * marked as seen, so the baseline exists by the time the device joins and its first force stop is reported.
     */
    suspend fun checkForceStops(context: Context) = stateLock.withLock {
        val app = context.applicationContext
        val am = app.getSystemService(ActivityManager::class.java)
        val lastUpdate = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        val stops = am.getHistoricalProcessExitReasons(app.packageName, 0, 0)
            .filter { ForceStopCheck.isUserForceStop(it.reason, it.timestamp, Build.VERSION.SDK_INT, lastUpdate) }
            .map { AppExit(it.timestamp, it.pid) }
        ForceStopCheck(state(app), recorder(app), gate(app)).onStart(stops)
    }

    suspend fun onSessionCompleted(context: Context, peer: DeviceId, atMs: Long) {
        state(context).setPeerSeen(peer, atMs)
    }

    /**
     * Leaving, removal or a new identity (LocalReset, core/data): no group file and no transport state of the old
     * group, under [stateLock] so no tracker writes a stale interval after the clear. The force-stop marks stay (see
     * TransportStateStore.clear). The group file is fsynced file I/O, so it runs on IO (preflight C20).
     *
     * Lock order is [stateLock], then the pipeline lock: the caller must NOT hold the pipeline lock (no
     * pipeline.exclusive around this), or it deadlocks against a tracker waiting in queueEvent. Call it before the
     * exclusive wipe: once the group file is gone the pipeline has no target and publishes nothing.
     */
    suspend fun forgetGroup(context: Context) = stateLock.withLock {
        withContext(Dispatchers.IO) { groups(context).delete() }
        state(context).clear()
    }

    /** Names for the notification line and whether they are the parents who see this child's data. */
    suspend fun notificationPeers(context: Context): NotificationPeers {
        val ctx = syncContext(context) ?: return NotificationPeers(emptyList(), parentsOnly = false)
        val state = Membership.compute(ctx.groupId, ctx.store.membershipEntries()) ?: return NotificationPeers(emptyList(), false)
        val self = ctx.keys.deviceId
        return NotificationPeers(StatusText.peerNames(state.active.values, self), parentsOnly = state.active[self]?.isChild == true)
    }

    private fun readGroupOrNull(context: Context): GroupConfig? = try {
        groups(context).read()
    } catch (e: GroupStoreException) {
        Log.w(TAG, "group file unreadable", e)
        null
    }

    private fun appVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
