package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.CloudPaths
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.GroupKeyRing
import app.kinsight.core.transport.cloud.OAuthException
import app.kinsight.core.transport.cloud.Pkce
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

enum class CloudEnableResult { ENABLED, RESTORED, NOT_CONFIGURED, NOT_ADMIN, ALREADY_ON, OLD_APP_IN_GROUP, SIGN_IN_EXPIRED, SIGN_IN_FAILED, NETWORK, STORAGE_FULL }

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
 * and lives in memory only.
 */
class CloudSignIn(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val appendOwn: OwnAppend,
    private val secrets: CloudSecrets,
    private val config: () -> CloudConfig,
    private val disk: (accessToken: suspend () -> String) -> DiskApi,
    private val oauth: (CloudConfig) -> YandexOAuth = { YandexOAuth(it.clientId, it.clientSecret) },
    private val onEnabled: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Pending(val verifier: String, val state: String, val url: String, val atMs: Long)

    /** Claimed (set to null) by a finish before its exchange, so a [start] meanwhile installs a new one that stays. */
    private val pending = AtomicReference<Pending?>(null)
    private val finishing = Mutex()

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /** Null when this device may sign in now, else why not. */
    suspend fun precheck(): CloudEnableResult? {
        if (!config().configured) return CloudEnableResult.NOT_CONFIGURED
        val group = withContext(io) { groups.read() } ?: return CloudEnableResult.NOT_ADMIN
        val self = keys().deviceId
        val store = RoomEntryStore(db, group.groupId)
        val m = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
        val me = m.active[self]
        if (me == null || !me.isAdmin || me.isChild) return CloudEnableResult.NOT_ADMIN
        val owner = m.storageOwner
        if (owner != null && owner.deviceId != self) return CloudEnableResult.ALREADY_ON
        if (owner == null && oldAppInGroup(store, m, self)) return CloudEnableResult.OLD_APP_IN_GROUP
        return null
    }

    /** The authorize address to open in the browser, or null (then [state] says why). */
    suspend fun start(deviceName: String): String? {
        precheck()?.let {
            _state.value = SignInState.Done(it)
            return null
        }
        val c = config()
        val verifier = Pkce.verifier(random)
        val st = Pkce.state(random)
        val url = oauth(c).authorizeUrl(Pkce.challenge(verifier), c.redirectUri, keys().deviceId.hex, deviceName, st)
        pending.set(Pending(verifier, st, url, clock()))
        _state.value = SignInState.Waiting(url)
        return url
    }

    /**
     * [input]: the pasted code (spaces allowed) or the app link the browser opened. Input that holds no code of this
     * sign-in returns [CloudEnableResult.SIGN_IN_FAILED] but keeps [state] at [SignInState.Waiting] (rejected), so the
     * user can paste again; a token endpoint out of reach returns [CloudEnableResult.NETWORK] and keeps it too. Only the
     * exchange's answer (or an expired sign-in) ends it. A finish arriving while another runs (the app link and a paste)
     * changes nothing: it waits for that one and returns its outcome.
     */
    suspend fun finish(input: String): CloudEnableResult {
        if (!finishing.tryLock()) {
            finishing.withLock { }
            return outcome()
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
            _state.value = SignInState.Waiting(p.url, rejected = true)
            return CloudEnableResult.SIGN_IN_FAILED
        }
        // A start() since we read it installed a newer sign-in: this input answered the old one.
        if (!pending.compareAndSet(p, null)) return CloudEnableResult.SIGN_IN_EXPIRED
        _state.value = SignInState.Working
        val tokens = try {
            oauth(config()).exchange(code, p.verifier)
        } catch (e: CancellationException) {
            reopen(p, networkError = false)
            throw e
        } catch (e: OAuthException) {
            // Spec §11: 5xx, 429 and the endpoint's own trouble codes are retried, like no answer at all.
            if (e.retryable) return reopen(p, networkError = true)
            Log.w(TAG, "sign-in refused: ${e.error}")
            return settle(CloudEnableResult.SIGN_IN_FAILED)
        } catch (e: IOException) {
            return reopen(p, networkError = true)
        }
        val result = try {
            enable(tokens)
        } catch (e: CancellationException) {
            settle(CloudEnableResult.SIGN_IN_FAILED)
            throw e
        } catch (e: Exception) {
            // The keystore or anything unexpected: never leave the screen at Working. The code is spent; start again.
            Log.w(TAG, "turning the cloud on failed: ${e.javaClass.simpleName}")
            CloudEnableResult.SIGN_IN_FAILED
        }
        if (result == CloudEnableResult.ENABLED || result == CloudEnableResult.RESTORED) onEnabled()
        return settle(result)
    }

    fun dismiss() {
        _state.value = SignInState.Idle
    }

    /** What a finish that waited for another reports: that one's outcome. */
    private fun outcome(): CloudEnableResult = when (val s = _state.value) {
        is SignInState.Done -> s.result
        is SignInState.Waiting -> if (s.networkError) CloudEnableResult.NETWORK else CloudEnableResult.SIGN_IN_FAILED
        SignInState.Idle, SignInState.Working -> CloudEnableResult.SIGN_IN_FAILED
    }

    /** Puts the claimed sign-in back for another try, unless a newer one was started meanwhile. */
    private fun reopen(p: Pending, networkError: Boolean): CloudEnableResult {
        if (pending.compareAndSet(null, p)) _state.value = SignInState.Waiting(p.url, networkError = networkError)
        return CloudEnableResult.NETWORK
    }

    /** Terminal outcome; a sign-in started meanwhile keeps its own Waiting state. */
    private fun settle(r: CloudEnableResult): CloudEnableResult {
        if (pending.get() == null) _state.value = SignInState.Done(r)
        return r
    }

    private suspend fun enable(t: TokenSet): CloudEnableResult {
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
            // Spec §11, 401 at the owner: new tokens and a new CloudAccess for the same storage.
            secrets.saveOwner(group.groupId, owner.generation, owner.storageId.toByteArray(), t)
            val (epoch, key) = writeKey(m, ring)
            val signed = appendOwn { a -> listOf(CloudAccessView.issue(a, m, epoch, key, t.accessToken, t.expiresAtMs)) }
            return if (signed) CloudEnableResult.RESTORED else CloudEnableResult.NOT_ADMIN
        }
        if (oldAppInGroup(store, m, self)) return CloudEnableResult.OLD_APP_IN_GROUP
        if (GroupKey.rotationNeeded(m) || GroupKey.membersWithoutKey(m).isNotEmpty()) {
            val before = m
            // Not rotated: sealing now would hand the storage to a key a departed phone holds.
            if (!appendOwn { a -> listOf(GroupKey.rotate(a, before)) }) return CloudEnableResult.NOT_ADMIN
            m = Membership.compute(group.groupId, store.membershipEntries()) ?: return CloudEnableResult.NOT_ADMIN
            GroupKey.open(k, m)?.let { (epoch, key) -> ring.put(epoch, key) }
        }
        val storageId = Primitives.randomBytes(16, random)
        val d = disk { t.accessToken }
        try {
            for (dir in listOf(CloudPaths.APP_ROOT, CloudPaths.root(group.groupId))) d.mkdir(dir)
            d.upload(CloudPaths.storageIdFile(group.groupId), storageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiskException.StorageFull) {
            return CloudEnableResult.STORAGE_FULL
        } catch (e: DiskException.Unauthorized) {
            return CloudEnableResult.SIGN_IN_FAILED
        } catch (e: IOException) {
            return CloudEnableResult.NETWORK
        }
        val (epoch, key) = writeKey(m, ring)
        val entries = store.membershipEntries()
        val signed = appendOwn { a ->
            val ownerEntry = a.storageOwnerEntry(self, storageId, 1)
            val withOwner = Membership.compute(group.groupId, entries + ownerEntry)!!
            listOf(ownerEntry, CloudAccessView.issue(a, withOwner, epoch, key, t.accessToken, t.expiresAtMs))
        }
        if (!signed) return CloudEnableResult.NOT_ADMIN
        // After the signed StorageOwner, so the vault never claims a storage no record names. Should this write fail, the
        // owner without tokens is asked to sign in again (spec §11), which restores them for this same storage.
        secrets.saveOwner(group.groupId, 1, storageId, t)
        return CloudEnableResult.ENABLED
    }

    /** The generation CloudSync seals with: the group's newest when held, else the newest held. */
    private suspend fun writeKey(m: MembershipState, ring: GroupKeyRing): Pair<Int, ByteArray> {
        val held = ring.keys()
        val current = GroupKey.currentEpoch(m)
        val epoch = if (current in held) current else held.keys.max()
        return epoch to held.getValue(epoch)
    }

    /** A member whose newest Heartbeat says protocol below 3 could not take the cloud entries. No Heartbeat: no refusal. */
    private suspend fun oldAppInGroup(store: RoomEntryStore, m: MembershipState, self: DeviceId): Boolean {
        val newest = store.heartbeats().groupBy { it.author }.mapValues { (_, beats) -> beats.maxBy { it.seq } }
        return m.active.keys.any { id -> id != self && (newest[id]?.body?.heartbeat?.protocol_version ?: MIN_CLOUD_PROTOCOL) < MIN_CLOUD_PROTOCOL }
    }

    companion object {
        /** A sign-in left open longer than this must start again. */
        const val PENDING_MS = 10L * 60 * 1000
        private const val MIN_CLOUD_PROTOCOL = 3
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
