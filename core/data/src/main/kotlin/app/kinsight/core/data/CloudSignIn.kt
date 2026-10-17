package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.storageHandoverEntry
import app.kinsight.core.sync.CLOUD_PROTOCOL_VERSION
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.GroupKeyRing
import app.kinsight.core.transport.cloud.OAuthException
import app.kinsight.core.transport.cloud.OwnerLifecycle
import app.kinsight.core.transport.cloud.PackCodec
import app.kinsight.core.transport.cloud.Pkce
import app.kinsight.core.transport.cloud.StorageMove
import app.kinsight.core.transport.cloud.StorageMoveException
import app.kinsight.core.transport.cloud.TokenSet
import app.kinsight.core.transport.cloud.YandexOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * The Yandex OAuth app of this build (BuildConfig, from local.properties; never in git). [redirectUri]: the verification
 * page (spec §3) until the Android platform is registered with the release SHA-256 (spec §14).
 */
data class CloudConfig(val clientId: String, val clientSecret: String, val redirectUri: String) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString() = "CloudConfig(clientId=$clientId, redirectUri=$redirectUri)"

    companion object {
        const val CODE_PAGE = "https://oauth.yandex.ru/verification_code"
        val NONE = CloudConfig("", "", CODE_PAGE)
    }
}

enum class CloudEnableResult {
    ENABLED, RESTORED, NOT_CONFIGURED, NOT_ADMIN, ALREADY_ON, OLD_APP_IN_GROUP, SIGN_IN_EXPIRED, SIGN_IN_FAILED, NETWORK, STORAGE_FULL,
    TAKEN_OVER, NOT_ALLOWED,
}

/** [ENABLE]: turn the cloud on, or the owner restores or renews its access; [TAKE_OVER]: take the storage (spec §7.1 to §7.3). */
enum class SignInMode { ENABLE, TAKE_OVER }

sealed interface SignInState {
    data object Idle : SignInState

    /**
     * The browser is open at [url]; the code comes back by the app link or the paste field. [rejected]: the last paste was
     * not a code of this sign-in (no code, or a link answering another sign-in). [networkError]: the token endpoint could
     * not be reached; the same code may be sent again. Either way the paste field stays for another try.
     */
    data class Waiting(val url: String, val rejected: Boolean = false, val networkError: Boolean = false) : SignInState

    data object Working : SignInState

    /**
     * Terminal: the code was exchanged (or the sign-in expired). [CloudEnableResult.NETWORK] here means the Disk failed
     * after the exchange: the code is spent, the user starts again (a network failure during the exchange stays [Waiting]).
     */
    data class Done(val result: CloudEnableResult) : SignInState
}

/**
 * «Синхронизировать через Яндекс Диск» (spec §6 «Включение облака»): PKCE sign-in, then the storage. Only an active admin
 * parent; refused while a member's newest Heartbeat says protocol below 3. Turning on: the group key is rotated first when
 * a departed phone still holds it or a member lacks the newest one, `storage.id` is created, StorageOwner and CloudAccess
 * are signed together, then the tokens go to the vault. The owner signing in again (spec §11, 401) gets new tokens and a
 * new CloudAccess for the same storage. One sign-in at a time ([finish] is serialized); a pending one lasts [PENDING_MS]
 * and lives in memory only. A parent asked by the owner, or one that finds the owner gone, takes the storage
 * ([SignInMode.TAKE_OVER]); the owner whose token a departed phone knew renews it (spec §7). Every sign-in brings a fresh
 * token, whose age starts then in [cloudState] (rulings Q19).
 */
class CloudSignIn(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val appendOwn: OwnAppend,
    private val secrets: CloudSecrets,
    private val cloudState: CloudStateStore,
    private val config: () -> CloudConfig,
    private val disk: (accessToken: suspend () -> String) -> DiskApi,
    private val oauth: (CloudConfig) -> YandexOAuth = { YandexOAuth(it.clientId, it.clientSecret) },
    private val onEnabled: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Pending(val verifier: String, val state: String, val url: String, val atMs: Long, val mode: SignInMode)

    /** Claimed (set to null) by a finish before its exchange, so a [start] meanwhile installs a new one that stays. */
    private val pending = AtomicReference<Pending?>(null)
    private val finishing = Mutex()

    /** [pending] and [state] change together under it, so a terminal outcome never hides a sign-in started meanwhile. */
    private val stateLock = Any()

    /** Counts [dismiss] calls (under [stateLock]): a claimed sign-in dismissed during its exchange is not put back. */
    private var dismissals = 0

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /** Null when this device may sign in now for [mode], else why not. */
    suspend fun precheck(mode: SignInMode = SignInMode.ENABLE): CloudEnableResult? {
        if (!config().configured) return CloudEnableResult.NOT_CONFIGURED
        val group = withContext(io) { groups.read() } ?: return CloudEnableResult.NOT_ADMIN
        val self = keys().deviceId
        val store = RoomEntryStore(db, group.groupId)
        val m = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
        val me = m.active[self]
        if (me == null || !me.isAdmin || me.isChild) return CloudEnableResult.NOT_ADMIN
        if (mode == SignInMode.TAKE_OVER) return if (mayTakeOver(store, m, self)) null else CloudEnableResult.NOT_ALLOWED
        val owner = m.storageOwner
        if (owner != null && owner.deviceId != self) return CloudEnableResult.ALREADY_ON
        if (owner == null && oldAppInGroup(store, m, self)) return CloudEnableResult.OLD_APP_IN_GROUP
        return null
    }

    /**
     * Spec §7.1, §7.2: asked by the owner, or the owner is gone or this phone has not seen its log grow for a week (rulings
     * Q14, Q17: this phone's own clock, never the owner's stamps).
     */
    private suspend fun mayTakeOver(store: RoomEntryStore, m: MembershipState, self: DeviceId): Boolean {
        val owner = m.storageOwner ?: return false
        val heard = cloudState.current().ownerHeardFrom(owner.deviceId)
        return OwnerLifecycle.canTakeOver(m, self, store.storageHandovers(), heard, clock())
    }

    /** The authorize address to open in the browser, or null (then [state] says why). */
    suspend fun start(deviceName: String, mode: SignInMode = SignInMode.ENABLE): String? {
        precheck(mode)?.let {
            _state.value = SignInState.Done(it)
            return null
        }
        val c = config()
        val verifier = Pkce.verifier(random)
        val st = Pkce.state(random)
        val url = oauth(c).authorizeUrl(Pkce.challenge(verifier), c.redirectUri, keys().deviceId.hex, deviceName, st)
        synchronized(stateLock) {
            pending.set(Pending(verifier, st, url, clock(), mode))
            _state.value = SignInState.Waiting(url)
        }
        return url
    }

    /**
     * [input]: the pasted code (spaces allowed) or the app link the browser opened. Input that holds no code of this
     * sign-in returns [CloudEnableResult.SIGN_IN_FAILED] but keeps [state] at [SignInState.Waiting] (rejected), so the
     * user can paste again; a token endpoint out of reach returns [CloudEnableResult.NETWORK] and keeps it too. Only the
     * exchange's answer (or an expired sign-in) ends it. A finish arriving while another runs (the app link and a paste)
     * waits for that one: when it ended the sign-in, the waiter returns its outcome; when it left it open (a rejected paste,
     * no answer from the endpoint), the waiter tries its own input.
     */
    suspend fun finish(input: String): CloudEnableResult {
        if (!finishing.tryLock()) {
            return finishing.withLock {
                if (pending.get() != null) finishLocked(input) else outcome()
            }
        }
        try {
            return finishLocked(input)
        } finally {
            finishing.unlock()
        }
    }

    private suspend fun finishLocked(input: String): CloudEnableResult {
        val p = pending.get()
        if (p == null || clock() - p.atMs !in 0..PENDING_MS) {
            pending.compareAndSet(p, null)
            return settle(CloudEnableResult.SIGN_IN_EXPIRED)
        }
        val code = codeOf(input, p.state)
        if (code == null) {
            synchronized(stateLock) { if (pending.get() === p) _state.value = SignInState.Waiting(p.url, rejected = true) }
            return CloudEnableResult.SIGN_IN_FAILED
        }
        // A start() since we read it installed a newer sign-in: this input answered the old one.
        var round = 0
        val claimed = synchronized(stateLock) {
            round = dismissals
            pending.compareAndSet(p, null).also { if (it) _state.value = SignInState.Working }
        }
        if (!claimed) return CloudEnableResult.SIGN_IN_EXPIRED
        val tokens = try {
            oauth(config()).exchange(code, p.verifier)
        } catch (e: CancellationException) {
            reopen(p, round, networkError = false)
            throw e
        } catch (e: OAuthException) {
            // Spec §11: 5xx, 429 and the endpoint's own trouble codes are retried, like no answer at all.
            if (e.retryable) return reopen(p, round, networkError = true)
            Log.w(TAG, "sign-in refused: ${e.error}")
            return settle(CloudEnableResult.SIGN_IN_FAILED)
        } catch (e: IOException) {
            return reopen(p, round, networkError = true)
        }
        val result = try {
            signedIn(tokens, p.mode)
        } catch (e: CancellationException) {
            settle(CloudEnableResult.SIGN_IN_FAILED)
            throw e
        }
        return settle(result)
    }

    /**
     * What follows a successful exchange: [enable] with [tokens], then [onEnabled] when this phone now holds an owner
     * token. Internal: [finish] calls it, and so does the debug build's CLOUD_FAKE_ENABLE (CloudDebugEnable, debug source
     * set only) with a fake TokenSet against the in-memory Disk, so the emulator checks run this same code path.
     */
    internal suspend fun signedIn(tokens: TokenSet, mode: SignInMode = SignInMode.ENABLE): CloudEnableResult {
        val result = try {
            enable(tokens, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The keystore or anything unexpected: never leave the screen at Working. The code is spent; start again.
            Log.w(TAG, "turning the cloud on failed: ${e.javaClass.simpleName}")
            CloudEnableResult.SIGN_IN_FAILED
        }
        if (result in SIGNED_IN) onEnabled()
        return result
    }

    /**
     * The dialog was closed: the sign-in is over. An app link or paste arriving later is SIGN_IN_EXPIRED; an exchange
     * already under way still reports its outcome, but a retryable failure does not bring the paste field back.
     */
    fun dismiss() {
        synchronized(stateLock) {
            pending.set(null)
            dismissals++
            _state.value = SignInState.Idle
        }
    }

    /** What a finish that waited for another reports: that one's outcome. */
    private fun outcome(): CloudEnableResult = when (val s = _state.value) {
        is SignInState.Done -> s.result
        is SignInState.Waiting -> if (s.networkError) CloudEnableResult.NETWORK else CloudEnableResult.SIGN_IN_FAILED
        SignInState.Idle, SignInState.Working -> CloudEnableResult.SIGN_IN_FAILED
    }

    /** Puts the claimed sign-in back for another try, unless a newer one was started or it was dismissed meanwhile. */
    private fun reopen(p: Pending, round: Int, networkError: Boolean): CloudEnableResult {
        synchronized(stateLock) {
            if (dismissals == round && pending.compareAndSet(null, p)) _state.value = SignInState.Waiting(p.url, networkError = networkError)
        }
        return CloudEnableResult.NETWORK
    }

    /** Terminal outcome; a sign-in started meanwhile keeps its own Waiting state. */
    private fun settle(r: CloudEnableResult): CloudEnableResult {
        synchronized(stateLock) {
            if (pending.get() == null) _state.value = SignInState.Done(r)
        }
        return r
    }

    /**
     * Under the process-wide cloud run lock (CloudSecrets.runLock; lock order: runLock, then the pipeline lock, as in
     * CloudRunner and LocalReset). A run in flight ends first, so the run after [onEnabled] is the one that sees the new
     * token; a wipe (LocalReset holds the lock) either ends before (then there is no group) or waits for this, so no old
     * group's tokens stay behind. The lock is not reentrant: nothing holding it may call [finish].
     */
    private suspend fun enable(t: TokenSet, mode: SignInMode): CloudEnableResult = secrets.runLock.withLock {
        // A token no CloudAccess can carry could never reach the other phones: nothing is written for it.
        if (!CloudAccessView.fits(t.accessToken, t.expiresAtMs)) {
            Log.w(TAG, "the access token is too large to hand out")
            return@withLock CloudEnableResult.SIGN_IN_FAILED
        }
        val result = if (mode == SignInMode.TAKE_OVER) takeOverLocked(t) else enableLocked(t)
        if (result in SIGNED_IN) freshToken()
        result
    }

    /** Rulings Q19: a sign-in brings a fresh token; its age (the refresh after 30 days) starts now. */
    private suspend fun freshToken() {
        try {
            val now = clock()
            cloudState.markRefreshCheck(now, tokenSinceMs = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only a hint: the weekly check starts the age itself when it finds none.
            Log.w(TAG, "token age not recorded: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun enableLocked(t: TokenSet): CloudEnableResult {
        val group = withContext(io) { groups.read() } ?: return CloudEnableResult.NOT_ADMIN
        val k = keys()
        val self = k.deviceId
        val store = RoomEntryStore(db, group.groupId)
        var m = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
        val me = m.active[self]
        if (me == null || !me.isAdmin || me.isChild) return CloudEnableResult.NOT_ADMIN
        val ring = secrets.ring(group)
        val owner = m.storageOwner
        if (owner != null) {
            if (owner.deviceId != self) return CloudEnableResult.ALREADY_ON
            // Spec §7.3: the storage.id on the new token's Disk decides. Missing or another one: another Yandex account, so
            // the storage moves there as in a take-over; never a new storage written under the old generation.
            val same = try {
                StorageMove.storageIdOf(disk { t.accessToken }, group.groupId)?.contentEquals(owner.storageId.toByteArray()) == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return diskFailure(e)
            }
            if (!same) {
                val held = secrets.tokens()?.takeIf { it.owner && it.groupHex == group.groupId.hex }
                return moveLocked(group, store, k, ring, owner, t, held?.storageGeneration, held?.accessToken, CloudEnableResult.RESTORED)
            }
            // Spec §7.4: a phone that knew the token left or was removed: a new generation with the new token.
            if (OwnerLifecycle.tokenExposed(m)) return renewLocked(group, store, k, m, ring, t)
            // Spec §11, 401 at the owner: new tokens and a new CloudAccess for the same storage.
            secrets.saveOwner(group.groupId, owner.generation, owner.storageId.toByteArray(), t)
            val signed = OwnerRecords.issue(appendOwn, k, m, ring, t.accessToken, t.expiresAtMs)
            return if (signed) CloudEnableResult.RESTORED else CloudEnableResult.NOT_ADMIN
        }
        if (oldAppInGroup(store, m, self)) return CloudEnableResult.OLD_APP_IN_GROUP
        m = rotatedIfNeeded(store, k, m, ring) ?: return CloudEnableResult.NOT_ADMIN
        val storageId = Primitives.randomBytes(16, random)
        val d = disk { t.accessToken }
        try {
            for (dir in listOf(CloudPaths.APP_ROOT, CloudPaths.root(group.groupId))) d.mkdir(dir)
            d.upload(CloudPaths.storageIdFile(group.groupId), storageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return diskFailure(e)
        }
        // Rulings Q3, Q15: the op names a fresh token id and every departure this phone knows of.
        if (OwnerRecords.claim(appendOwn, store, k, ring, storageId, 1, t.accessToken, t.expiresAtMs) == null) return CloudEnableResult.NOT_ADMIN
        // After the signed StorageOwner, so the vault never claims a storage no record names. Should this write fail, the
        // owner without tokens is asked to sign in again (spec §11), which restores them for this same storage.
        secrets.saveOwner(group.groupId, 1, storageId, t)
        return CloudEnableResult.ENABLED
    }

    /** Rotates first when a departed phone holds the group key or a member lacks it; the membership after, null when not signed. */
    private suspend fun rotatedIfNeeded(store: RoomEntryStore, k: DeviceKeys, m: MembershipState, ring: GroupKeyRing): MembershipState? {
        if (!GroupKey.rotationNeeded(m) && GroupKey.membersWithoutKey(m).isEmpty()) return m
        // Not rotated: sealing now would hand the storage to a key a departed phone holds.
        if (!appendOwn { a -> listOf(GroupKey.rotate(a, m)) }) return null
        val after = Membership.compute(store.groupId, store.membershipEntries()) ?: return null
        GroupKey.open(k, after)?.let { (epoch, key) -> ring.put(epoch, key) }
        return after
    }

    /**
     * Spec §7.4: rotation first, then StorageOwner(generation + 1, the same storage) naming a fresh token and every known
     * departure (rulings Q3), and CloudAccess with the new token; the old token is retired for the revoke.
     */
    private suspend fun renewLocked(group: GroupConfig, store: RoomEntryStore, k: DeviceKeys, start: MembershipState, ring: GroupKeyRing, t: TokenSet): CloudEnableResult {
        val m = rotatedIfNeeded(store, k, start, ring) ?: return CloudEnableResult.NOT_ADMIN
        val owner = m.storageOwner ?: return CloudEnableResult.NOT_ADMIN
        val storageId = owner.storageId.toByteArray()
        val generation = owner.generation + 1
        val old = secrets.tokens()?.takeIf { it.owner && it.groupHex == group.groupId.hex }
        OwnerRecords.claim(appendOwn, store, k, ring, storageId, generation, t.accessToken, t.expiresAtMs) ?: return CloudEnableResult.NOT_ADMIN
        old?.takeIf { it.accessToken != t.accessToken }?.let { secrets.retire(group.groupId, it.storageGeneration, it.accessToken) }
        secrets.saveOwner(group.groupId, generation, storageId, t)
        return CloudEnableResult.RESTORED
    }

    /** Spec §7.1 to §7.3: this parent, asked or finding the owner gone, takes the storage with the token it was handed. */
    private suspend fun takeOverLocked(t: TokenSet): CloudEnableResult {
        val group = withContext(io) { groups.read() } ?: return CloudEnableResult.NOT_ADMIN
        val k = keys()
        val self = k.deviceId
        val store = RoomEntryStore(db, group.groupId)
        val start = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
        if (!mayTakeOver(store, start, self)) return CloudEnableResult.NOT_ALLOWED
        val ring = secrets.ring(group)
        val owner = start.storageOwner ?: return CloudEnableResult.NOT_ALLOWED
        val old = secrets.access(store, start, self, ring)
        return moveLocked(group, store, k, ring, owner, t, old?.generation, old?.accessToken, CloudEnableResult.TAKEN_OVER)
    }

    /**
     * This phone becomes the owner of the storage on [t]'s Disk (spec §7.1 to §7.3). The same storage.id there: only the
     * role moves, the id stays. Otherwise the family folder is copied there with [oldToken] (a 401 from the old Disk means
     * it was revoked: the new storage fills from the phones' own copies, spec §7.2), a fresh storage.id is written (rulings
     * Q5), StorageOwner(generation + 1) and CloudAccess are signed, [oldToken] is retired, and the «переезд» goes into the
     * old storage. A failure of the new Disk signs nothing (rulings Q2): the old storage stays in force.
     */
    private suspend fun moveLocked(
        group: GroupConfig,
        store: RoomEntryStore,
        k: DeviceKeys,
        ring: GroupKeyRing,
        owner: StorageOwnerState,
        t: TokenSet,
        oldGeneration: Long?,
        oldToken: String?,
        result: CloudEnableResult,
    ): CloudEnableResult {
        val newDisk = disk { t.accessToken }
        val oldDisk = oldToken?.let { a -> disk { a } }
        val current = owner.storageId.toByteArray()
        var storageId = current
        try {
            val same = StorageMove.storageIdOf(newDisk, group.groupId)?.contentEquals(current) == true
            if (!same) {
                storageId = Primitives.randomBytes(16, random)
                if (oldDisk != null) {
                    try {
                        StorageMove.copy(oldDisk, newDisk, group.groupId)
                    } catch (e: StorageMoveException) {
                        if (e.side != StorageMove.Side.SOURCE || e.cause !is DiskException.Unauthorized) throw e
                        Log.i(TAG, "the old storage is out of reach: the new one fills from the phones' own copies")
                    }
                }
                StorageMove.writeStorageId(newDisk, group.groupId, storageId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return diskFailure(e)
        }
        // After the Disk work, so a failed Disk signs nothing; the rotation keeps the new token from a departed phone. Read
        // again: a Bluetooth session may have changed the members or the key during the copy.
        val fresh = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
        rotatedIfNeeded(store, k, fresh, ring) ?: return CloudEnableResult.NOT_ADMIN
        val generation = owner.generation + 1
        val claimed = OwnerRecords.claim(appendOwn, store, k, ring, storageId, generation, t.accessToken, t.expiresAtMs)
            ?: return CloudEnableResult.NOT_ADMIN
        if (oldToken != null && oldGeneration != null && oldToken != t.accessToken) secrets.retire(group.groupId, oldGeneration, oldToken)
        secrets.saveOwner(group.groupId, generation, storageId, t)
        if (!storageId.contentEquals(current) && oldDisk != null) leaveMoveRecord(oldDisk, store, k, claimed)
        return result
    }

    /** A Disk failure while setting the storage up; a [StorageMoveException] stands for the Disk failure it carries. */
    private fun diskFailure(e: IOException): CloudEnableResult = when ((e as? StorageMoveException)?.cause ?: e) {
        is DiskException.StorageFull -> CloudEnableResult.STORAGE_FULL
        is DiskException.Unauthorized -> CloudEnableResult.SIGN_IN_FAILED
        else -> CloudEnableResult.NETWORK
    }

    /**
     * Spec §7.1 «кладёт в старое хранилище запись «переезд»»: the new StorageOwner and CloudAccess, sealed with the group
     * key, in this phone's folder of the old storage, so phones still on it switch at their next run. The rotation op of
     * that key generation travels in clear next to it (rulings Q11), so a phone that has not heard of a rotation signed
     * for the take-over opens it. Best effort: a phone that misses it gets the new token over Bluetooth from the new owner
     * (spec §6).
     */
    private suspend fun leaveMoveRecord(oldDisk: DiskApi, store: RoomEntryStore, k: DeviceKeys, c: Claimed) {
        val groupId: GroupId = store.groupId
        val from = c.entries.first().seq
        val to = c.entries.last().seq
        val dir = CloudPaths.deviceDir(groupId, k.deviceId)
        try {
            val m = Membership.compute(groupId, store.membershipEntries())
            val rotation = if (c.epoch == 0) emptyList() else m?.effectiveEntries?.filter { it.body.membership_op?.rotate?.epoch == c.epoch }.orEmpty()
            val pack = PackCodec.seal(k, groupId, c.entries, from, to, c.epoch, c.groupKey, clock(), rotation)
            for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(groupId), dir)) oldDisk.mkdir(d)
            oldDisk.upload("$dir/${CloudPaths.packName(from, to)}", pack)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "the move record did not reach the old storage: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Spec §7.1 «Владелец выбирает родителя»: the owner asks [to], another active parent, to take the storage. Only an
     * admin parent may own it (spec §3), so a parent who is not one yet is made an admin in the same signed batch.
     */
    suspend fun requestHandover(to: DeviceId): Boolean {
        val group = withContext(io) { groups.read() } ?: return false
        val self = keys().deviceId
        val m = Membership.compute(group.groupId, RoomEntryStore(db, group.groupId).membershipEntries()) ?: return false
        val owner = m.storageOwner ?: return false
        val target = m.active[to] ?: return false
        if (owner.deviceId != self || self !in m.active || to == self || target.isChild) return false
        // Each create takes the next seq: the grant is created only when it is signed.
        return appendOwn { a -> listOfNotNull(if (target.isAdmin) null else a.grantAdminEntry(to), a.storageHandoverEntry(to, owner.generation)) }
    }

    /** A member whose newest Heartbeat says protocol below 3 could not take the cloud entries. No Heartbeat: no refusal. */
    private suspend fun oldAppInGroup(store: RoomEntryStore, m: MembershipState, self: DeviceId): Boolean {
        val newest = store.heartbeats().groupBy { it.author }.mapValues { (_, beats) -> beats.maxBy { it.seq } }
        return m.active.keys.any { id -> id != self && (newest[id]?.body?.heartbeat?.protocol_version ?: CLOUD_PROTOCOL_VERSION) < CLOUD_PROTOCOL_VERSION }
    }

    companion object {
        /** A sign-in left open longer than this must start again. */
        const val PENDING_MS = 10L * 60 * 1000

        /** The outcomes that leave this phone with a fresh owner token. */
        private val SIGNED_IN = setOf(CloudEnableResult.ENABLED, CloudEnableResult.RESTORED, CloudEnableResult.TAKEN_OVER)
        private const val TAG = "KinsightCloud"
        private val BARE_CODE = Regex("^[0-9A-Za-z]{4,64}$")

        /**
         * The code in what came back: an app link (its state must be [state]) or the bare code the verification page shows,
         * spaces and line breaks around it allowed. Null for anything else.
         */
        fun codeOf(input: String, state: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            if ("code=" !in s) return s.takeIf { BARE_CODE.matches(it) }
            val params = s.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { kv ->
                val parts = kv.split('=', limit = 2)
                if (parts.size != 2) {
                    null
                } else {
                    try {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        null // a malformed %-escape: not ours
                    }
                }
            }.toMap()
            if (params["state"] != state) return null
            return params["code"]?.trim()?.takeIf { BARE_CODE.matches(it) }
        }
    }
}
