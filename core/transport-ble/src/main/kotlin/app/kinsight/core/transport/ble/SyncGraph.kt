package app.kinsight.core.transport.ble

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.usage.PublishTarget
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    internal fun publish(s: ServiceSnapshot) {
        _snapshot.value = s
    }

    /** From Application.onCreate: usage entries get signed once a group exists. */
    fun install(context: Context) {
        val app = context.applicationContext
        UsageGraph.targetProvider = { target(app) }
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
        return SyncContext(
            groupId = group.groupId,
            discoveryKey = group.discoveryKey,
            keys = keys,
            store = RoomEntryStore(KinsightDatabaseFactory.shared(context), group.groupId),
            policy = SyncPolicy(
                self = keys.deviceId,
                showOthers = true, // the «show others» switch arrives in stage 4
                detailRetentionDays = settings.detailRetentionDays.toLong(),
                summaryRetentionDays = settings.summaryRetentionDays.toLong(),
            ),
        )
    }

    /** Before a session: fresh own usage (spec §7, force) and the daily Heartbeat. Never inside the pipeline lock. */
    suspend fun prepare(context: Context) {
        UsageGraph.pipeline(context).collectAndPublish(force = true)
        emitHeartbeatIfDue(context)
    }

    fun heartbeats(context: Context): Heartbeats {
        val app = context.applicationContext
        return Heartbeats(state(app), appVersion(app), append = { build -> UsageGraph.pipeline(app).appendOwn(build = build) })
    }

    fun bluetoothOff(context: Context): BluetoothOffTracker {
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

    /** Display names of the other active members (for the notification); empty without a group or membership. */
    suspend fun peerNames(context: Context): List<String> {
        val ctx = syncContext(context) ?: return emptyList()
        val members = Membership.compute(ctx.groupId, ctx.store.membershipEntries())?.active?.values ?: return emptyList()
        return StatusText.peerNames(members, ctx.keys.deviceId)
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
