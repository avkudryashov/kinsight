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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom

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
     * not a code of this sign-in (no code, or a link answering another sign-in); the paste field stays for another try.
     */
    data class Waiting(val url: String, val rejected: Boolean = false) : SignInState

    data object Working : SignInState

    data class Done(val result: CloudEnableResult) : SignInState
}

/**
 * «Синхронизировать через Яндекс Диск» (spec §6 «Включение облака»): PKCE sign-in, then the storage. Only an active admin
 * parent; refused while a member's newest Heartbeat says protocol below 3. Turning on: the group key is rotated first when
 * a departed phone still holds it or a member lacks the newest one, `storage.id` is created, the tokens go to the vault,
 * then StorageOwner and CloudAccess are signed together. The owner signing in again (spec §11, 401) gets new tokens and a
 * new CloudAccess for the same storage. One sign-in at a time; a pending one lasts [PENDING_MS] and lives in memory only.
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

    @Volatile
    private var pending: Pending? = null

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
        pending = Pending(verifier, st, url, clock())
        _state.value = SignInState.Waiting(url)
        return url
    }

    /**
     * [input]: the pasted code (spaces allowed) or the app link the browser opened. Input that holds no code of this
     * sign-in returns [CloudEnableResult.SIGN_IN_FAILED] but keeps [state] at [SignInState.Waiting] (rejected), so the
     * user can paste again; only the token exchange (or an expired sign-in) ends it.
     */
    suspend fun finish(input: String): CloudEnableResult {
        val p = pending?.takeIf { clock() - it.atMs in 0..PENDING_MS }
            ?: return done(CloudEnableResult.SIGN_IN_EXPIRED).also { pending = null }
        val code = codeOf(input, p.state)
        if (code == null) {
            _state.value = SignInState.Waiting(p.url, rejected = true)
            return CloudEnableResult.SIGN_IN_FAILED
        }
        _state.value = SignInState.Working
        val tokens = try {
            oauth(config()).exchange(code, p.verifier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OAuthException) {
            Log.w(TAG, "sign-in refused: ${e.error}")
            pending = null
            return done(CloudEnableResult.SIGN_IN_FAILED)
        } catch (e: IOException) {
            return done(CloudEnableResult.NETWORK)
        }
        pending = null
        val result = enable(tokens)
        if (result == CloudEnableResult.ENABLED || result == CloudEnableResult.RESTORED) onEnabled()
        return done(result)
    }

    fun dismiss() {
        _state.value = SignInState.Idle
    }

    private fun done(r: CloudEnableResult): CloudEnableResult {
        _state.value = SignInState.Done(r)
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
            appendOwn { a -> listOf(GroupKey.rotate(a, before)) }
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
        secrets.saveOwner(group.groupId, 1, storageId, t)
        val (epoch, key) = writeKey(m, ring)
        val entries = store.membershipEntries()
        val signed = appendOwn { a ->
            val ownerEntry = a.storageOwnerEntry(self, storageId, 1)
            val withOwner = Membership.compute(group.groupId, entries + ownerEntry)!!
            listOf(ownerEntry, CloudAccessView.issue(a, withOwner, epoch, key, t.accessToken, t.expiresAtMs))
        }
        return if (signed) CloudEnableResult.ENABLED else CloudEnableResult.NOT_ADMIN
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
