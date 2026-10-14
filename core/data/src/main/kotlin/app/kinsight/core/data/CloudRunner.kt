package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomCloudCursorStore
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.SyncPolicies
import app.kinsight.core.transport.cloud.CloudResult
import app.kinsight.core.transport.cloud.CloudSync
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.usage.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [PERIODIC]: the worker's schedule (a child may skip); [NOW]: the app was opened, a hand-off arrived, a debug hook. */
enum class CloudTrigger { PERIODIC, NOW }

enum class CloudRun { OFF, SKIPPED, SYNCED, NO_ACCESS, UNAUTHORIZED, STORAGE_FULL, RETRY }

/**
 * One cloud run of this device (spec §9, §11): nothing without a storage owner; a child's periodic run only when its own
 * log moved or an hour passed; an admin first rotates the group key for a member without the newest one (Y1 review);
 * then CloudSync with this device's access. The whole run holds [runLock], one per process, so the CloudSync gets a lock
 * of its own that is never contended. One instance is kept per group, device and policy (Y1 review: one CloudSync per
 * device); it reaches the Disk of the current run ([disk] is asked again every run), so switching the Disk (the debug
 * CLOUD_FAKE_DISK) takes effect at the next run. The outcome goes to [state].
 */
class CloudRunner(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val settings: SettingsStore,
    private val secrets: CloudSecrets,
    private val state: CloudStateStore,
    private val appendOwn: OwnAppend,
    private val disk: (accessToken: suspend () -> String) -> DiskApi,
    /** The process-wide run lock (CloudSecrets.runLock), shared with the local wipe (R14, R15). */
    val runLock: Mutex,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Key(val groupId: GroupId, val self: DeviceId, val showOthers: Boolean, val detailDays: Long, val summaryDays: Long)

    @Volatile
    private var cached: Pair<Key, CloudSync>? = null

    /** The token of the current run; the Disk client asks for it on every request. */
    @Volatile
    private var token: String? = null

    /** The Disk of the current run; the cached CloudSync reaches it through [runDisk]. */
    @Volatile
    private var currentDisk: DiskApi? = null

    private val runDisk = object : DiskApi {
        private fun d() = currentDisk ?: throw DiskException.Unauthorized()

        override suspend fun list(path: String) = d().list(path)

        override suspend fun mkdir(path: String) = d().mkdir(path)

        override suspend fun upload(path: String, bytes: ByteArray) = d().upload(path, bytes)

        override suspend fun download(path: String) = d().download(path)

        override suspend fun delete(path: String) = d().delete(path)
    }

    /**
     * True while this device is an active member of a group with a storage owner: the worker is scheduled only then.
     * The same conditions as the run's OFF checks, so a removed device does not schedule runs that only cancel themselves.
     */
    suspend fun isOn(): Boolean {
        val group = withContext(io) { groups.read() } ?: return false
        val membership = Membership.compute(group.groupId, RoomEntryStore(db, group.groupId).membershipEntries()) ?: return false
        return membership.storageOwner != null && keys().deviceId in membership.active
    }

    /**
     * The whole run holds [runLock], the group file and membership are read under it: a run queued behind a local wipe
     * (LocalReset holds the same lock across it) finds no group and does nothing, so it cannot write the old group's
     * keys back. Lock order: runLock, then the pipeline lock (the rotation's appendOwn), as in LocalReset.
     */
    suspend fun run(trigger: CloudTrigger): CloudRun = runLock.withLock { runLocked(trigger) }

    private suspend fun runLocked(trigger: CloudTrigger): CloudRun {
        val group = withContext(io) { groups.read() } ?: return CloudRun.OFF
        val k = keys()
        val self = k.deviceId
        val store = RoomEntryStore(db, group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudRun.OFF
        val me = membership.active[self] ?: return CloudRun.OFF
        val owner = membership.storageOwner ?: return CloudRun.OFF
        val now = clock()
        val cursors = RoomCloudCursorStore(db) { Log.w(TAG, it) }
        if (me.isChild && trigger == CloudTrigger.PERIODIC) {
            // Spec §9: a child uploads every 15 minutes when there is something new, otherwise once an hour.
            val moved = store.versionVector()[self] > cursors.load().uploadedUpTo
            val last = state.current().lastRunMs
            if (!moved && last != null && now - last in 0 until CHILD_IDLE_MS) return CloudRun.SKIPPED
        }
        if (me.isAdmin && GroupKey.membersWithoutKey(membership).isNotEmpty()) {
            Log.i(TAG, "a member lacks the newest group key: rotating again")
            appendOwn { author -> listOf(GroupKey.rotate(author, membership)) }
        }
        val ring = secrets.ring(group)
        val isOwner = owner.deviceId == self
        // R16: another device owns the storage now; a vault still saying owner loses that role and its refresh token.
        if (!isOwner) secrets.dropStaleOwner(group.groupId, membership, self)
        val access = secrets.access(store, membership, self, ring)
            ?: return if (isOwner) {
                // The owner lost its tokens (an unreadable vault): only a new sign-in brings them back.
                record(now, CloudRun.UNAUTHORIZED, CloudProblem.UNAUTHORIZED)
            } else {
                record(now, CloudRun.NO_ACCESS, CloudProblem.NO_ACCESS)
            }
        token = access.accessToken
        currentDisk = disk { token ?: throw DiskException.Unauthorized() }
        val policy = SyncPolicies.forDevice(membership, self, settings.current())
        val key = Key(group.groupId, self, policy.showOthers, policy.detailRetentionDays, policy.summaryRetentionDays)
        val sync = cached?.takeIf { it.first == key }?.second
            ?: CloudSync(
                store, k, policy, runDisk, ring, cursors, clock,
                log = { Log.i(TAG, it) }, running = Mutex(), // [runLock] is already held around the whole run
            ).also { cached = key to it }
        return when (sync.run()) {
            is CloudResult.Synced -> record(now, CloudRun.SYNCED, null, synced = true)
            CloudResult.Unauthorized -> record(now, CloudRun.UNAUTHORIZED, if (isOwner) CloudProblem.UNAUTHORIZED else CloudProblem.NO_ACCESS)
            CloudResult.StorageFull -> record(now, CloudRun.STORAGE_FULL, CloudProblem.STORAGE_FULL)
            // Rare (every rotation is sealed to the owner): a new sign-in cannot fix a missing key, so the owner is not
            // asked for one; it waits like on a network problem (no message). Another device waits for the owner.
            CloudResult.NoGroupKey -> record(now, CloudRun.NO_ACCESS, if (isOwner) CloudProblem.NETWORK else CloudProblem.NO_ACCESS)
            CloudResult.NotMember -> CloudRun.OFF
            is CloudResult.Retry -> record(now, CloudRun.RETRY, CloudProblem.NETWORK)
        }
    }

    /** One line for the debug hook CLOUD_STATUS. Never a token. */
    suspend fun describe(): String {
        val group = withContext(io) { groups.read() } ?: return "cloud=off group=none"
        val self = keys().deviceId
        val store = RoomEntryStore(db, group.groupId)
        val m = Membership.compute(group.groupId, store.membershipEntries()) ?: return "cloud=off membership=none"
        val owner = m.storageOwner ?: return "cloud=off"
        val ring = secrets.ring(group)
        val access = secrets.access(store, m, self, ring)
        val s = state.current()
        return "cloud=on owner=${owner.deviceId.hex.take(8)} self=${if (owner.deviceId == self) "owner" else "member"} " +
            "generation=${owner.generation} keys=${ring.keys().keys.sorted()} access=${if (access != null) "yes" else "no"} " +
            "expiresAtMs=${access?.expiresAtMs} refresh=${secrets.tokens()?.refreshToken != null} " +
            "uploadedUpTo=${RoomCloudCursorStore(db).load().uploadedUpTo} lastSyncMs=${s.lastSyncMs} problem=${s.problem}"
    }

    private suspend fun record(now: Long, run: CloudRun, problem: CloudProblem?, synced: Boolean = false): CloudRun {
        state.record(now, synced, problem)
        return run
    }

    companion object {
        /** Spec §9: without anything new a child uploads once an hour. */
        const val CHILD_IDLE_MS = 60L * 60 * 1000
        private const val TAG = "KinsightCloud"
    }
}
