package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.keystore.CloudTokenVault
import app.kinsight.core.keystore.CloudTokens
import app.kinsight.core.keystore.GroupKeyFile
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.keystore.RetiredToken
import app.kinsight.core.keystore.RetiredTokenFile
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.PendingRevoke
import app.kinsight.core.sync.WelcomeCloud
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.GroupKeyRing
import app.kinsight.core.transport.cloud.StorageAccess
import app.kinsight.core.transport.cloud.TokenSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * This device's cloud secrets (spec §5, §6 «Хранение»): the token vault, the group key ring and the tokens it no longer
 * uses but must still revoke (spec §7), all sealed files of core/keystore. Wiped with the group (LocalReset).
 * [onAccess] runs after a handed token was stored (the app schedules the cloud worker then; at a join the group file does
 * not exist yet, so the pairing's onJoined schedules it). [runLock]: the process-wide cloud run lock (R15): the CloudRunner
 * holds it for a whole run, LocalReset for a whole wipe and CloudSignIn while it turns the cloud on, so neither a run nor a sign-in can write the old
 * group's key or tokens back after the wipe.
 */
class CloudSecrets(
    private val vault: CloudTokenVault,
    private val keyFile: GroupKeyFile,
    private val retiredFile: RetiredTokenFile,
    private val onAccess: () -> Unit = {},
    val runLock: Mutex = Mutex(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val vaultLock = Mutex()
    private val ringLock = Mutex()
    private val retiredLock = Mutex()

    /** The persisted ring of [group]: generation 0 = the discovery key whenever the file holds nothing for this group. */
    fun ring(group: GroupConfig): GroupKeyRing = FileKeyRing(group)

    /** Null when there are none or the vault is unreadable (then the device waits for a new hand-off or sign-in). */
    suspend fun tokens(): CloudTokens? = withContext(io) {
        try {
            vault.read()
        } catch (e: KeyStorageException) {
            Log.w(TAG, "cloud tokens unreadable", e)
            null
        }
    }

    /**
     * The owner's tokens after a sign-in (spec §6: the refresh token stays on the owner's phone). The owner is a parent: an
     * access token of the group it replaces is retired first, for the revoke.
     */
    suspend fun saveOwner(groupId: GroupId, generation: Long, storageId: ByteArray, t: TokenSet): Unit = vaultLock.withLock {
        retireReplaced(groupId, tokens(), t.accessToken)
        withContext(io) { vault.write(CloudTokens(groupId.hex, owner = true, generation, storageId, t.accessToken, t.refreshToken, t.expiresAtMs)) }
    }

    /**
     * A token handed at pairing or over Bluetooth. Never replaces the owner's own tokens of the group (whatever storage
     * generation it names), a token of a newer storage generation, nor a newer token (a later expiry) of the same one.
     * True when stored. On a parent ([keep]) the token it replaces is retired first, for the revoke; a child just replaces it.
     */
    suspend fun saveHanded(groupId: GroupId, a: HandedAccess, keep: Boolean): Boolean = vaultLock.withLock {
        val held = tokens()
        if (held != null && held.groupHex == groupId.hex) {
            if (held.owner) return@withLock false
            if (held.storageGeneration > a.storageGeneration) return@withLock false
            if (held.storageGeneration == a.storageGeneration && (held.accessToken == a.accessToken || held.expiresAtMs > a.expiresAtMs)) {
                return@withLock false
            }
        }
        if (keep) retireReplaced(groupId, held, a.accessToken)
        withContext(io) { vault.write(CloudTokens(groupId.hex, owner = false, a.storageGeneration, a.storageId, a.accessToken, null, a.expiresAtMs)) }
        onAccess()
        true
    }

    /**
     * The access this device uses for the current storage (spec §6): the owner its own token; every other device the newer
     * (later expiry) of the owner's newest CloudAccess it can open («новый доступ», spec §7.4) and the token handed to it.
     * Null without a storage owner or without any access yet.
     */
    suspend fun access(store: EntryStore, membership: MembershipState, self: DeviceId, ring: GroupKeyRing): StorageAccess? {
        val owner = membership.storageOwner ?: return null
        val storageId = owner.storageId.toByteArray()
        val held = tokens()?.takeIf {
            it.groupHex == store.groupId.hex && it.storageGeneration == owner.generation && it.storageId.contentEquals(storageId)
        }
        if (held != null && held.owner && owner.deviceId == self) {
            return StorageAccess(owner.deviceId, storageId, owner.generation, held.accessToken, held.expiresAtMs)
        }
        val fromLog = CloudAccessView.current(store, membership, ring.keys())
        val handed = held?.takeIf { !it.owner }?.let { StorageAccess(owner.deviceId, storageId, owner.generation, it.accessToken, it.expiresAtMs) }
        return listOfNotNull(fromLog, handed).maxByOrNull { it.expiresAtMs }
    }

    /**
     * What an admin hands a joiner (spec §6 «Раздача»): its newest group key generation and, only when this device owns
     * the storage («владелец передаёт access_token»), the access it holds. Another parent's joiner gets the token at its
     * next Bluetooth meeting with the owner. [withRefresh]: the owner's transfer invite to its own new phone (spec §7.3)
     * also hands the refresh token of the access it hands, and the tokens this phone still has to revoke (spec §7.1, §7.4:
     * it leaves right after, so the new owner revokes them); no other welcome does (spec §6: the refresh token stays on
     * the owner).
     */
    suspend fun welcome(group: GroupConfig, store: EntryStore, membership: MembershipState, self: DeviceId, withRefresh: Boolean = false): WelcomeCloud {
        val ring = ring(group)
        val held = ring.keys()
        val current = GroupKey.currentEpoch(membership)
        val epoch = if (current in held) current else held.keys.maxOrNull() ?: 0
        val key = held[epoch]?.takeIf { epoch > 0 }
        val access = access(store, membership, self, ring)
            ?.takeIf { membership.storageOwner?.deviceId == self }
            ?.let { HandedAccess(it.generation, it.storageId, it.accessToken, it.expiresAtMs) }
        // Spec §7.3: only the owner's transfer invite hands the refresh token, and only with its own access.
        val refresh = if (withRefresh && access != null) {
            transferTokens(group.groupId, membership, self)?.takeIf { it.accessToken == access.accessToken }?.refreshToken
        } else {
            null
        }
        val retired = if (refresh == null) {
            emptyList()
        } else {
            retired(group.groupId).filter { it.accessToken != access!!.accessToken }.map { PendingRevoke(it.generation, it.accessToken) }
        }
        return WelcomeCloud(if (key == null) 0 else epoch, key, access, refresh, retired)
    }

    /**
     * Spec §7.3: the owner's own tokens of the access in force (this group, the StorageOwner's generation and storage)
     * when they hold a refresh token, so a transfer invite can hand it; null on any other phone or without one (a Y2-era
     * owner, an access taken from the log). The one rule for the transfer invite ([welcome]) and for offering it
     * ([CloudStatus.canTransfer]); a sign-in by the owner stores a refresh token again.
     */
    suspend fun transferTokens(groupId: GroupId, membership: MembershipState, self: DeviceId): CloudTokens? {
        val owner = membership.storageOwner?.takeIf { it.deviceId == self } ?: return null
        val storageId = owner.storageId.toByteArray()
        return tokens()?.takeIf {
            it.owner && it.groupHex == groupId.hex && it.storageGeneration == owner.generation && it.storageId.contentEquals(storageId) &&
                !it.refreshToken.isNullOrEmpty()
        }
    }

    /** The joiner's side, before its group file is written: a ring for the new group and the handed access, nothing older. */
    suspend fun adopt(groupId: GroupId, discoveryKey: ByteArray, cloud: WelcomeCloud?) {
        val keys = buildMap {
            put(0, discoveryKey.copyOf())
            cloud?.groupKey?.let { put(cloud.keyEpoch, it.copyOf()) }
        }
        ringLock.withLock { withContext(io) { keyFile.write(GroupKeyFile.Contents(groupId.hex, keys)) } }
        vaultLock.withLock { withContext(io) { vault.delete() } }
        // The vault is empty here: nothing to retire.
        cloud?.access?.let { saveHanded(groupId, it, keep = false) }
    }

    /**
     * Leaving, a removal or a new identity (LocalReset): no token and no key of the old group stays. The caller holds
     * [runLock] across the whole wipe (LocalReset does), so no cloud run can write the old ring back.
     */
    suspend fun forget() {
        vaultLock.withLock { withContext(io) { vault.delete() } }
        ringLock.withLock { withContext(io) { keyFile.delete() } }
        retiredLock.withLock { withContext(io) { retiredFile.delete() } }
    }

    /**
     * The vault says this device owns the storage of [groupId], but the StorageOwner record names another device (two
     * parents turned the cloud on at once and the other one won, or a later hand-over): the owner role and the refresh
     * token go, so handed tokens are accepted again. The whole vault goes: its access token belongs to the owner role (a
     * storage this device created) and, kept as a member's, could outrank the real owner's handed token. True when
     * dropped. Its access token is retired first: it is revoked once every phone switched (spec §7.1).
     */
    suspend fun dropStaleOwner(groupId: GroupId, membership: MembershipState, self: DeviceId): Boolean = vaultLock.withLock {
        val owner = membership.storageOwner ?: return@withLock false
        if (owner.deviceId == self) return@withLock false
        val held = tokens()?.takeIf { it.owner && it.groupHex == groupId.hex } ?: return@withLock false
        // Kept for the revoke (roadmap: the old token must be revoked, never just lost with the vault).
        retire(groupId, held.storageGeneration, held.accessToken)
        withContext(io) { vault.delete() }
        Log.i(TAG, "another device owns the storage: dropping the owner role")
        true
    }

    /** Keeps [accessToken] of storage generation [generation] until it is revoked, once every phone switched (spec §7.1, §7.4). */
    suspend fun retire(groupId: GroupId, generation: Long, accessToken: String): Unit = retiredLock.withLock {
        withContext(io) {
            val held = readRetired() ?: emptyList()
            if (held.none { it.accessToken == accessToken }) {
                val next = held + RetiredToken(groupId.hex, generation, accessToken)
                val evicted = next.size - RetiredTokenFile.MAX
                if (evicted > 0) Log.w(TAG, "retired tokens over ${RetiredTokenFile.MAX}: the oldest $evicted dropped unrevoked")
                retiredFile.write(next)
            }
        }
    }

    /** The retired tokens of [groupId], oldest first. */
    suspend fun retired(groupId: GroupId): List<RetiredToken> = retiredLock.withLock {
        withContext(io) { readRetired().orEmpty().filter { it.groupHex == groupId.hex } }
    }

    /** Revoked, or still in use (spec §7.3 keeps the token): no longer kept. An unreadable list stays as it is (logged). */
    suspend fun dropRetired(accessToken: String): Unit = retiredLock.withLock {
        withContext(io) {
            val held = readRetired() ?: return@withContext
            if (held.any { it.accessToken == accessToken }) retiredFile.write(held.filter { it.accessToken != accessToken })
        }
    }

    /** A different access token of [groupId] in [held] is about to be overwritten: kept for the revoke. */
    private suspend fun retireReplaced(groupId: GroupId, held: CloudTokens?, next: String) {
        if (held != null && held.groupHex == groupId.hex && held.accessToken != next) retire(groupId, held.storageGeneration, held.accessToken)
    }

    /**
     * A handed token of an older storage generation than the StorageOwner record names is no longer used (spec §7: every
     * generation change replaces the token): it leaves the vault, and a parent ([keep]) keeps it for the revoke. True when
     * the vault changed.
     */
    suspend fun retireStale(groupId: GroupId, membership: MembershipState, keep: Boolean): Boolean = vaultLock.withLock {
        val owner = membership.storageOwner ?: return@withLock false
        val held = tokens()?.takeIf { !it.owner && it.groupHex == groupId.hex && it.storageGeneration < owner.generation }
            ?: return@withLock false
        if (keep) retire(groupId, held.storageGeneration, held.accessToken)
        withContext(io) { vault.delete() }
        true
    }

    /** Null when the file is unreadable (logged). */
    private fun readRetired(): List<RetiredToken>? = try {
        retiredFile.read()
    } catch (e: KeyStorageException) {
        Log.w(TAG, "retired tokens unreadable", e)
        null
    }

    private inner class FileKeyRing(private val group: GroupConfig) : GroupKeyRing {
        override suspend fun keys(): Map<Int, ByteArray> = ringLock.withLock { load() }

        override suspend fun put(epoch: Int, key: ByteArray) = ringLock.withLock { save(load() + (epoch to key.copyOf())) }

        override suspend fun retain(keep: Set<Int>) = ringLock.withLock { save(load().filterKeys { it in keep }) }

        private suspend fun load(): Map<Int, ByteArray> = withContext(io) {
            val held = try {
                keyFile.read()
            } catch (e: KeyStorageException) {
                // Newer generations come back through membership (CloudSync.absorbRotation opens the newest one).
                Log.w(TAG, "group keys unreadable: starting again from generation 0", e)
                null
            }
            // Nothing written here: the file of another group stays until a put or retain of this group replaces it.
            if (held != null && held.groupHex == group.groupId.hex) held.keys else mapOf(0 to group.discoveryKey.copyOf())
        }

        private suspend fun save(keys: Map<Int, ByteArray>) = withContext(io) { keyFile.write(GroupKeyFile.Contents(group.groupId.hex, keys)) }
    }

    private companion object {
        const val TAG = "KinsightCloud"
    }
}
