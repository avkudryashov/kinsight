package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.keystore.CloudTokenVault
import app.kinsight.core.keystore.CloudTokens
import app.kinsight.core.keystore.GroupKeyFile
import app.kinsight.core.keystore.KeyStorageException
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.HandedAccess
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
 * This device's cloud secrets (spec §5, §6 «Хранение»): the token vault and the group key ring, both sealed files of
 * core/keystore. Wiped with the group (LocalReset). [onAccess] runs after a handed token was stored (the app schedules
 * the cloud worker then; at a join the group file does not exist yet, so the pairing's onJoined schedules it). [runLock]: the process-wide cloud run lock (R15): the CloudRunner holds it for a whole run and
 * LocalReset for a whole wipe, so a run cannot write the old group's key back after the wipe.
 */
class CloudSecrets(
    private val vault: CloudTokenVault,
    private val keyFile: GroupKeyFile,
    private val onAccess: () -> Unit = {},
    val runLock: Mutex = Mutex(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val vaultLock = Mutex()
    private val ringLock = Mutex()

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

    /** The owner's tokens after a sign-in (spec §6: the refresh token stays on the owner's phone). */
    suspend fun saveOwner(groupId: GroupId, generation: Long, storageId: ByteArray, t: TokenSet): Unit = vaultLock.withLock {
        withContext(io) { vault.write(CloudTokens(groupId.hex, owner = true, generation, storageId, t.accessToken, t.refreshToken, t.expiresAtMs)) }
    }

    /**
     * A token handed at pairing or over Bluetooth. Never replaces the owner's own tokens of the group (whatever storage
     * generation it names), a token of a newer storage generation, nor a newer token (a later expiry) of the same one.
     * True when stored.
     */
    suspend fun saveHanded(groupId: GroupId, a: HandedAccess): Boolean = vaultLock.withLock {
        val held = tokens()
        if (held != null && held.groupHex == groupId.hex) {
            if (held.owner) return@withLock false
            if (held.storageGeneration > a.storageGeneration) return@withLock false
            if (held.storageGeneration == a.storageGeneration && (held.accessToken == a.accessToken || held.expiresAtMs > a.expiresAtMs)) {
                return@withLock false
            }
        }
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
     * next Bluetooth meeting with the owner.
     */
    suspend fun welcome(group: GroupConfig, store: EntryStore, membership: MembershipState, self: DeviceId): WelcomeCloud {
        val ring = ring(group)
        val held = ring.keys()
        val current = GroupKey.currentEpoch(membership)
        val epoch = if (current in held) current else held.keys.maxOrNull() ?: 0
        val key = held[epoch]?.takeIf { epoch > 0 }
        val access = access(store, membership, self, ring)
            ?.takeIf { membership.storageOwner?.deviceId == self }
            ?.let { HandedAccess(it.generation, it.storageId, it.accessToken, it.expiresAtMs) }
        return WelcomeCloud(if (key == null) 0 else epoch, key, access)
    }

    /** The joiner's side, before its group file is written: a ring for the new group and the handed access, nothing older. */
    suspend fun adopt(groupId: GroupId, discoveryKey: ByteArray, cloud: WelcomeCloud?) {
        val keys = buildMap {
            put(0, discoveryKey.copyOf())
            cloud?.groupKey?.let { put(cloud.keyEpoch, it.copyOf()) }
        }
        ringLock.withLock { withContext(io) { keyFile.write(GroupKeyFile.Contents(groupId.hex, keys)) } }
        vaultLock.withLock { withContext(io) { vault.delete() } }
        cloud?.access?.let { saveHanded(groupId, it) }
    }

    /**
     * Leaving, a removal or a new identity (LocalReset): no token and no key of the old group stays. The caller holds
     * [runLock] across the whole wipe (LocalReset does), so no cloud run can write the old ring back.
     */
    suspend fun forget() {
        vaultLock.withLock { withContext(io) { vault.delete() } }
        ringLock.withLock { withContext(io) { keyFile.delete() } }
    }

    /**
     * The vault says this device owns the storage of [groupId], but the StorageOwner record names another device (two
     * parents turned the cloud on at once and the other one won, or a later hand-over): the owner role and the refresh
     * token go, so handed tokens are accepted again. The whole vault goes: its access token belongs to the owner role (a
     * storage this device created) and, kept as a member's, could outrank the real owner's handed token. True when
     * dropped.
     */
    suspend fun dropStaleOwner(groupId: GroupId, membership: MembershipState, self: DeviceId): Boolean = vaultLock.withLock {
        val owner = membership.storageOwner ?: return@withLock false
        if (owner.deviceId == self) return@withLock false
        tokens()?.takeIf { it.owner && it.groupHex == groupId.hex } ?: return@withLock false
        withContext(io) { vault.delete() }
        Log.i(TAG, "another device owns the storage: dropping the owner role")
        true
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
