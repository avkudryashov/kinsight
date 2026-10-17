package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomCloudCursorStore
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.SyncPolicies
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudResult
import app.kinsight.core.transport.cloud.CloudSync
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.GroupKeyRing
import app.kinsight.core.transport.cloud.OAuthException
import app.kinsight.core.transport.cloud.OwnerLifecycle
import app.kinsight.core.transport.cloud.TokenSet
import app.kinsight.core.transport.cloud.YandexOAuth
import app.kinsight.core.usage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** [PERIODIC]: the worker's schedule (a child may skip); [NOW]: the app was opened, a hand-off arrived, a debug hook. */
enum class CloudTrigger { PERIODIC, NOW }

enum class CloudRun { OFF, SKIPPED, SYNCED, NO_ACCESS, UNAUTHORIZED, STORAGE_FULL, RETRY }

/** A run and how many entries of others it brought (Y4 final review: a zone exit raises its alert at once, spec §9). */
data class CloudRunOutcome(val run: CloudRun, val received: Int)

/**
 * One cloud run of this device (spec §9, §11): nothing without a storage owner; a child's periodic run only when its own
 * log moved or an hour passed; an admin first rotates the group key for a member without the newest one (Y1 review);
 * then CloudSync with this device's access. The whole run holds [runLock], one per process, so the CloudSync gets a lock
 * of its own that is never contended. One instance is kept per group, device and policy (Y1 review: one CloudSync per
 * device); it reaches the Disk of the current run ([disk] is asked again every run), so switching the Disk (the debug
 * CLOUD_FAKE_DISK) takes effect at the next run. The outcome goes to [state]. The owner also refreshes its token weekly,
 * re-issues an access that no longer opens, and any parent revokes retired tokens once every phone switched (spec §6, §7).
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
    /** The process-wide run lock (CloudSecrets.runLock), shared with the local wipe and the sign-in (R14, R15). */
    val runLock: Mutex,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The build's Yandex client: the owner's refresh and the revoke of retired tokens need its secret (spec §2, §6). */
    private val config: () -> CloudConfig = { CloudConfig.NONE },
    private val oauth: (CloudConfig) -> YandexOAuth = { YandexOAuth(it.clientId, it.clientSecret) },
    /** After the first sync with a storage generation: the app signs a Heartbeat saying so (spec §7 «подтвердили»). */
    private val afterSync: suspend () -> Unit = {},
) {
    private data class Key(
        val groupId: GroupId,
        val self: DeviceId,
        val showOthers: Boolean,
        val detailDays: Long,
        val summaryDays: Long,
        val ownedStorage: String?,
    )

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
    suspend fun run(trigger: CloudTrigger): CloudRun = runCounted(trigger).run

    /** [run], with the number of entries of others the sync brought (read under the same [runLock] hold). */
    suspend fun runCounted(trigger: CloudTrigger): CloudRunOutcome = runLock.withLock {
        received = 0
        val run = runLocked(trigger)
        CloudRunOutcome(run, received)
    }

    /** Entries of others the current run's sync brought; written and read only under [runLock]. */
    private var received = 0

    private suspend fun runLocked(trigger: CloudTrigger): CloudRun {
        val group = withContext(io) { groups.read() } ?: return CloudRun.OFF
        val k = keys()
        val self = k.deviceId
        val store = RoomEntryStore(db, group.groupId)
        var membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudRun.OFF
        val me = membership.active[self] ?: return CloudRun.OFF
        val owner = membership.storageOwner ?: return CloudRun.OFF
        val consent = settings.current()
        if (me.isChild && (consent.consentAtMs == null || consent.consentOutdated)) {
            // Roadmap Y5, privacy policy §13: a child that never agreed, or agreed only to the Bluetooth-only text, sends
            // nothing through the Disk (no Heartbeat, no membership pack either) until it agreed to the text that names
            // it; Bluetooth goes on as before. SKIPPED keeps the schedule for the run after its «Принимаю».
            return CloudRun.SKIPPED
        }
        val now = clock()
        val cursors = RoomCloudCursorStore(db) { Log.w(TAG, it) }
        if (me.isChild && trigger == CloudTrigger.PERIODIC) {
            // Spec §9: a child uploads every 15 minutes when there is something new, otherwise once an hour.
            val moved = store.versionVector()[self] > cursors.load().uploadedUpTo
            val last = state.current().lastRunMs
            if (!moved && last != null && now - last in 0 until CHILD_IDLE_MS) return CloudRun.SKIPPED
        }
        val isOwner = owner.deviceId == self
        if (me.isAdmin) {
            // Spec §5, §7.4: the key changes when a phone leaves or is removed. The owner rotates; another admin only while
            // the owner is unavailable (departed or not heard here for a week, spec §7.2), so two admins rarely rotate at
            // once (KeyConflict settles it deterministically when they do). A member without the newest generation is
            // rotated for by any admin (Y1 review).
            val ownerGone = OwnerLifecycle.ownerUnavailable(membership, state.current().ownerHeardFrom(owner.deviceId), now)
            val departedHoldsKey = GroupKey.rotationNeeded(membership) && (isOwner || ownerGone)
            if (departedHoldsKey || GroupKey.membersWithoutKey(membership).isNotEmpty()) {
                Log.i(TAG, "rotating the group key")
                appendOwn { author -> listOf(GroupKey.rotate(author, membership)) }
                // Rulings Q8: what follows (a refreshed token above all) is sealed under the rotation just signed.
                membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudRun.OFF
            }
        }
        val ring = secrets.ring(group)
        // Y3 parked concern: the newest generation sealed to us may have come over Bluetooth only (the rotation op), and
        // the owner's CloudAccess in the log is sealed with it: the ring takes it now, not only inside a sync this phone
        // could never start without an access.
        GroupKey.open(k, membership)?.let { (epoch, key) -> if (ring.keys()[epoch]?.contentEquals(key) != true) ring.put(epoch, key) }
        if (isOwner) {
            // Not rotated (the append found no target): a refreshed token would be sealed under a key a departed phone holds.
            if (!GroupKey.rotationNeeded(membership)) refreshIfDue(group.groupId, membership, k, ring, now)
        } else {
            // R16: another device owns the storage now; a vault still saying owner loses that role (its token is retired).
            secrets.dropStaleOwner(group.groupId, membership, self)
            // Spec §7: a handed token of an older generation goes once the new storage's access opens here (rulings Q13);
            // a parent keeps it for the revoke, a child never retires.
            dropFollowed(store, group.groupId, membership, ring, keep = !me.isChild)
            if (!me.isChild) retireOlderAccess(store, membership, ring)
        }
        // Spec §7.1 «переключаются при следующей синхронизации» (fix round 1): no access of the current generation opens
        // yet (the new StorageOwner came over Bluetooth, the CloudAccess did not), so the older handed token still held
        // reads its own storage, where the «переезд» pack (or the renewed CloudAccess, §7.4) waits.
        val access = secrets.access(store, membership, self, ring)
        val previous = if (isOwner || access != null) null else olderHanded(group.groupId, owner.generation)
        val accessToken = access?.accessToken ?: previous?.accessToken
            ?: return if (isOwner) {
                // The owner lost its tokens (an unreadable vault): only a new sign-in brings them back.
                record(now, CloudRun.UNAUTHORIZED, CloudProblem.UNAUTHORIZED)
            } else {
                record(now, CloudRun.NO_ACCESS, CloudProblem.NO_ACCESS)
            }
        // Parked Y3 minor: an older token the old Disk already refused is not tried again on every run (battery) until
        // something changes: a new owner record, another kept token (its fingerprint is in the mark), or the new access
        // opening here.
        val oldMark = previous?.let { oldTokenMark(it.storageGeneration, owner.generation, owner.deviceId, it.accessToken) }
        if (oldMark != null && state.current().deadOldToken == oldMark) return record(now, CloudRun.UNAUTHORIZED, CloudProblem.NO_ACCESS)
        // The generation this run actually syncs with: the Heartbeat never claims a storage this phone has not reached.
        val syncedGeneration = if (access != null) owner.generation else previous!!.storageGeneration
        token = accessToken
        currentDisk = disk { token ?: throw DiskException.Unauthorized() }
        val policy = SyncPolicies.forDevice(membership, self, settings.current())
        val ownedStorage = if (isOwner) owner.storageId.toByteArray() else null
        val key = Key(group.groupId, self, policy.showOthers, policy.detailRetentionDays, policy.summaryRetentionDays, ownedStorage?.toHex())
        val sync = cached?.takeIf { it.first == key }?.second
            ?: CloudSync(
                store, k, policy, runDisk, ring, cursors, clock,
                log = { Log.i(TAG, it) }, running = Mutex(), // [runLock] is already held around the whole run
                ownedStorageId = ownedStorage,
            ).also { cached = key to it }
        val result = sync.run()
        if (result is CloudResult.Synced) received = result.received
        if (result is CloudResult.StorageFull) received = result.received
        observeOwner(store, membership, now)
        val after = Membership.compute(group.groupId, store.membershipEntries())
        if (!isOwner && after != null) {
            dropFollowed(store, group.groupId, after, ring, keep = !me.isChild)
            if (!me.isChild) retireOlderAccess(store, after, ring)
        }
        // The old token's run revokes nothing: it is still in use and must not be dropped as such.
        if (result is CloudResult.Synced && access != null && after != null) afterSynced(store, group.groupId, after, k, ring, access.accessToken)
        return when (result) {
            is CloudResult.Synced -> {
                // Rulings Q18: the generation is recorded before the Heartbeat reads it, so a phone that only meets the
                // cloud confirms the switch too.
                // A new generation owes a Heartbeat, kept in the state (parked Y3 minor: across a process death too); one
                // that failed is tried again at the next synced run (it is idempotent by its mark).
                record(now, CloudRun.SYNCED, null, synced = true, generation = syncedGeneration).also {
                    if (state.current().heartbeatPending && signHeartbeat()) state.heartbeatSigned()
                }
            }
            CloudResult.Unauthorized -> {
                if (oldMark != null) state.markDeadOldToken(oldMark)
                record(now, CloudRun.UNAUTHORIZED, if (isOwner) CloudProblem.UNAUTHORIZED else CloudProblem.NO_ACCESS)
            }
            is CloudResult.StorageFull -> record(now, CloudRun.STORAGE_FULL, CloudProblem.STORAGE_FULL)
            // Rare (every rotation is sealed to the owner): a new sign-in cannot fix a missing key, so the owner is not
            // asked for one; it waits like on a network problem (no message). Another device waits for the owner.
            CloudResult.NoGroupKey -> record(now, CloudRun.NO_ACCESS, if (isOwner) CloudProblem.NETWORK else CloudProblem.NO_ACCESS)
            CloudResult.NotMember -> CloudRun.OFF
            is CloudResult.Retry -> record(now, CloudRun.RETRY, CloudProblem.NETWORK)
        }
    }

    /**
     * Rulings Q13, Q19: a handed token of an older storage generation leaves the vault only once the new storage's access
     * opens here: this phone read the «переезд» pack (or the renewed CloudAccess) with it, or the owner's CloudAccess came
     * over Bluetooth. Until then it stays and reads the old storage. A parent ([keep]) keeps it for the revoke.
     */
    private suspend fun dropFollowed(store: RoomEntryStore, groupId: GroupId, m: MembershipState, ring: GroupKeyRing, keep: Boolean) {
        if (CloudAccessView.current(store, m, ring.keys()) != null) secrets.retireStale(groupId, m, keep = keep)
    }

    /**
     * Final review, Important 3: once the storage generation moved on, a parent keeps the previous generation's
     * CloudAccess token for the revoke too (its vault may hold none: its access came from the log). Before the sync too,
     * while the ring still holds that CloudAccess's key, and after it, once the sync absorbed a key the ring lacked.
     * Marked once dealt with ([CloudState.olderAccessGeneration]), so a token revoked and dropped is never taken again.
     */
    private suspend fun retireOlderAccess(store: RoomEntryStore, m: MembershipState, ring: GroupKeyRing) {
        val older = OwnerLifecycle.previousOwner(m) ?: return
        if (older.generation <= (state.current().olderAccessGeneration ?: 0L)) return
        if (secrets.retireOlderAccess(store, m, older, ring)) state.markOlderAccess(older.generation)
    }


    /** The handed token of an older storage generation of [groupId] still in the vault, or null. */
    private suspend fun olderHanded(groupId: GroupId, generation: Long) =
        secrets.tokens()?.takeIf { !it.owner && it.groupHex == groupId.hex && it.storageGeneration < generation }

    /** After a sync (its rotations are in the ring, [m] read after it): the owner's access re-issued when it no longer opens; retired tokens revoked. */
    private suspend fun afterSynced(store: RoomEntryStore, groupId: GroupId, m: MembershipState, k: DeviceKeys, ring: GroupKeyRing, inUse: String) {
        reissueIfUnreadable(store, m, k, ring)
        revokeRetired(groupId, store, m, k.deviceId, ring, inUse)
    }

    /**
     * Y1 review: two admins rotated at once and the other key won, so the CloudAccess this owner sealed with its own key
     * opens for nobody. Issued again under the generation it seals with now. Also when the access in force does not carry
     * the vault's token (a refresh whose CloudAccess append failed).
     */
    private suspend fun reissueIfUnreadable(store: RoomEntryStore, m: MembershipState, k: DeviceKeys, ring: GroupKeyRing) {
        val owner = m.storageOwner ?: return
        if (owner.deviceId != k.deviceId) return
        val held = secrets.tokens()?.takeIf {
            it.owner && it.groupHex == store.groupId.hex && it.storageGeneration == owner.generation &&
                it.storageId.contentEquals(owner.storageId.toByteArray())
        } ?: return
        if (CloudAccessView.current(store, m, ring.keys())?.accessToken == held.accessToken) return
        Log.i(TAG, "our CloudAccess does not open under the group key that won, or is not our token: issuing it again")
        OwnerRecords.issue(appendOwn, k, m, ring, held.accessToken, held.expiresAtMs)
    }

    /**
     * Spec §6 «Продление», rulings Q9: once a week the owner checks its token and refreshes it with the client secret when
     * it was last refreshed (or first checked here) [REFRESH_AGE_MS] ago or fewer than [REFRESH_BEFORE_MS] are left. A new
     * access token goes out as a CloudAccess («новый доступ»), sealed under [m], the membership after this run's rotation
     * (rulings Q8). A refused refresh is logged: the Disk's 401 asks the owner to sign in (spec §11). No answer: tried
     * again at the next run. Under [CloudSecrets.refreshLock]: a transfer welcome waits for it, or it sees the transfer.
     */
    private suspend fun refreshIfDue(groupId: GroupId, m: MembershipState, k: DeviceKeys, ring: GroupKeyRing, now: Long) =
        secrets.refreshLock.withLock { refreshLocked(groupId, m, k, ring, now) }

    private suspend fun refreshLocked(groupId: GroupId, m: MembershipState, k: DeviceKeys, ring: GroupKeyRing, now: Long) {
        val s = state.current()
        // Spec §7.3: while this phone's transfer invite may still hand its refresh token, a refresh could kill the handed one.
        if (OwnerTransfers.phase(s.transfer, groupId, m, now) != null) return
        val last = s.refreshCheckMs
        if (last != null && now - last in 0 until REFRESH_CHECK_MS) return
        val c = config()
        val owner = m.storageOwner ?: return
        // Only the vault of the storage in force: a stale owner vault of an older generation is never refreshed.
        val held = secrets.tokens()?.takeIf {
            it.owner && it.groupHex == groupId.hex && it.storageGeneration == owner.generation &&
                it.storageId.contentEquals(owner.storageId.toByteArray())
        } ?: return
        val refresh = held.refreshToken
        if (!c.configured || refresh == null) return
        // A first check (or a clock set back past the last refresh) starts the token's age now.
        val since = s.tokenSinceMs?.takeIf { it <= now }
        val old = since != null && now - since >= REFRESH_AGE_MS
        if (held.expiresAtMs - now >= REFRESH_BEFORE_MS && !old) {
            state.markRefreshCheck(now, tokenSinceMs = if (since == null) now else null)
            return
        }
        val t = try {
            oauth(c).refresh(refresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OAuthException) {
            if (e.retryable) return
            Log.w(TAG, "token refresh refused: ${e.error}")
            state.markRefreshCheck(now)
            return
        } catch (e: IOException) {
            return
        }
        secrets.saveOwner(groupId, held.storageGeneration, held.storageId, TokenSet(t.accessToken, t.refreshToken ?: refresh, t.expiresAtMs))
        if (t.accessToken != held.accessToken && !OwnerRecords.issue(appendOwn, k, m, ring, t.accessToken, t.expiresAtMs)) {
            Log.w(TAG, "refreshed access not signed")
        }
        state.markRefreshCheck(now, tokenSinceMs = now)
    }

    /**
     * Spec §7.1, §7.4: a retired token is revoked once every other active member's newest Heartbeat reports a later storage
     * generation. One still in use is dropped without a revoke: this run's token, the token of the CloudAccess in force,
     * or a token the StorageOwner op in force kept (the same token_id as the op of its generation, spec §7.3, rulings Q3:
     * a refresh may since have moved this phone to a new token while others still use the kept one). An answer of any
     * kind ends it (a token Yandex no longer knows is dead anyway); no answer: the next run tries again.
     */
    private suspend fun revokeRetired(
        groupId: GroupId,
        store: RoomEntryStore,
        m: MembershipState,
        self: DeviceId,
        ring: GroupKeyRing,
        inUse: String,
    ) {
        val retired = secrets.retired(groupId)
        if (retired.isEmpty()) return
        val c = config()
        val beats = store.heartbeats()
        val shared = CloudAccessView.current(store, m, ring.keys())?.accessToken
        for (r in retired) {
            if (r.accessToken == inUse || r.accessToken == shared || keptByOwnerInForce(m, r.generation)) {
                secrets.dropRetired(r.accessToken)
                continue
            }
            if (!c.configured || !OwnerLifecycle.allSwitched(m, beats, r.generation, self)) continue
            try {
                val revoked = oauth(c).revoke(r.accessToken)
                Log.i(TAG, "retired token of generation ${r.generation}: revoked=$revoked")
                secrets.dropRetired(r.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "revoke failed, retried at the next run: ${e.javaClass.simpleName}")
            }
        }
    }

    /** True when the StorageOwner op of [generation] names the same (non-empty) token_id as the op in force (rulings Q3). */
    private fun keptByOwnerInForce(m: MembershipState, generation: Long): Boolean {
        val current = m.storageOwner ?: return false
        if (current.tokenId.size == 0 || current.generation == generation) return false
        val op = m.effectiveEntries.mapNotNull { it.body.membership_op?.storage_owner }.firstOrNull { it.generation == generation } ?: return false
        return op.token_id == current.tokenId
    }

    /** True when the Heartbeat was signed (or nothing was due). */
    private suspend fun signHeartbeat(): Boolean = try {
        afterSync()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "no Heartbeat after a new storage generation: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Rulings Q14: notes the storage owner's newest seq here, heard on this device's clock when it grew. After every
     * cloud run and every Bluetooth session (the app's session hook). Rulings Q18: under [runLock] (never called while
     * holding it), so a note queued behind a wipe finds no group and writes nothing back after the cleared cloud state.
     */
    suspend fun noteOwnerHeard(): Unit = runLock.withLock {
        val group = withContext(io) { groups.read() } ?: return@withLock
        val store = RoomEntryStore(db, group.groupId)
        val membership = Membership.compute(group.groupId, store.membershipEntries()) ?: return@withLock
        observeOwner(store, membership, clock())
    }

    private suspend fun observeOwner(store: RoomEntryStore, membership: MembershipState, nowMs: Long) {
        val owner = membership.storageOwner ?: return
        state.observeOwner(owner.deviceId, store.versionVector()[owner.deviceId], nowMs)
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

    private suspend fun record(now: Long, run: CloudRun, problem: CloudProblem?, synced: Boolean = false, generation: Long? = null): CloudRun {
        state.record(now, synced, problem, generation)
        return run
    }

    companion object {
        /**
         * What a 401 to the kept older token is remembered under: its generation, the owner record in force and a
         * fingerprint of the token (8 bytes of SHA-256, never the token), so another token of the same generation is tried.
         */
        internal fun oldTokenMark(heldGeneration: Long, ownerGeneration: Long, owner: DeviceId, token: String): String =
            "$heldGeneration/$ownerGeneration/${owner.hex}/${Primitives.sha256(token.encodeToByteArray()).copyOf(8).toHex()}"

        /** Spec §9: without anything new a child uploads once an hour. */
        const val CHILD_IDLE_MS = 60L * 60 * 1000

        /** Spec §6: the owner checks its token weekly. */
        const val REFRESH_CHECK_MS = 7L * 24 * 60 * 60 * 1000

        /** And refreshes it when fewer than 30 days are left (roadmap Y3). */
        const val REFRESH_BEFORE_MS = 30L * 24 * 60 * 60 * 1000

        /** Or when it was last refreshed 30 days ago (rulings Q9, spec §6 «раз в неделю-месяц»). */
        const val REFRESH_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val TAG = "KinsightCloud"
    }
}
