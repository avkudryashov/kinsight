package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.CloudGrant
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.CloudAccessRules
import app.kinsight.core.sync.EntryStore
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer

/** The storage access this device can use (spec §6). Secret: never log [accessToken]. */
class StorageAccess(val owner: DeviceId, val storageId: ByteArray, val generation: Long, val accessToken: String, val expiresAtMs: Long) {
    override fun toString() = "StorageAccess(owner=${owner.hex.take(8)}, generation=$generation, expiresAtMs=$expiresAtMs)"
}

/**
 * The «новый доступ» record (spec §6, §7.4): the owner's access token, encrypted with the group key of [CloudAccess.key_epoch]
 * so it never appears in the log in clear and a device that left before that generation cannot read it. The refresh token
 * is never put here (spec §6: only the device that takes the owner role gets it, directly).
 */
object CloudAccessView {
    fun issue(
        author: EntryAuthor,
        membership: MembershipState,
        keyEpoch: Int,
        groupKey: ByteArray,
        accessToken: String,
        expiresAtMs: Long,
    ): DecodedEntry {
        val owner = requireNotNull(membership.storageOwner) { "no storage owner" }
        require(owner.deviceId == author.keys.deviceId) { "only the storage owner issues access" }
        val nonce = Primitives.randomBytes(12)
        val plain = CloudGrant.ADAPTER.encode(CloudGrant(access_token = accessToken, expires_at_ms = expiresAtMs))
        val aad = aad(membership.groupId, owner.generation, owner.storageId.toByteArray(), keyEpoch)
        val ciphertext = Primitives.chachaSeal(GroupKey.accessKey(groupKey), nonce, aad, plain)
        val access = CloudAccess(
            generation = owner.generation,
            storage_id = owner.storageId,
            key_epoch = keyEpoch,
            nonce = nonce.toByteString(),
            ciphertext = ciphertext.toByteString(),
        )
        // Checked before signing, as every receiver checks it (CloudAccessRules): an entry a child's rules refuse would
        // keep the owner's log open on that child for good (SyncSession.holdsBack), and a signed seq cannot be taken back.
        require(membership.roleOf(owner.deviceId) != DeviceRole.CHILD) { "cloud access from a child" }
        CloudAccessRules.fieldViolation(access)?.let { throw IllegalArgumentException("cloud access refused: $it") }
        return author.create { it.copy(cloud_access = access) }
    }

    /**
     * The current StorageOwner's newest CloudAccess for the current storage generation, decrypted with [ring]; null when
     * there is no owner, it has not issued one for this generation yet, or this device lacks that key generation.
     */
    fun current(membership: MembershipState, entries: List<DecodedEntry>, ring: Map<Int, ByteArray>): StorageAccess? {
        val owner = membership.storageOwner ?: return null
        val access = entries.filter { it.author == owner.deviceId && it.kind == EntryKind.CLOUD_ACCESS }
            .maxByOrNull { it.seq }?.body?.cloud_access ?: return null
        if (access.generation != owner.generation || access.storage_id != owner.storageId) return null
        return open(membership.groupId, owner, access, ring)
    }

    /**
     * [owner]'s newest CLOUD_ACCESS entry for [owner]'s own generation and storage (any StorageOwner op, an older one
     * too), paging through [owner]'s log only; null when there is none. [open] decrypts it.
     */
    suspend fun newestFor(store: EntryStore, owner: StorageOwnerState, page: Int = PAGE): DecodedEntry? {
        var newest: DecodedEntry? = null
        var after = 0L
        while (true) {
            val entries = store.entriesAfter(owner.deviceId, after, page)
            if (entries.isEmpty()) break
            entries.lastOrNull { e ->
                e.kind == EntryKind.CLOUD_ACCESS && e.body.cloud_access?.let { it.generation == owner.generation && it.storage_id == owner.storageId } == true
            }?.let { newest = it }
            after = entries.last().seq
        }
        return newest
    }

    /** The access [entry] grants for [owner]'s generation and storage, decrypted with [ring]; null when it does not open. */
    fun open(groupId: GroupId, owner: StorageOwnerState, entry: DecodedEntry, ring: Map<Int, ByteArray>): StorageAccess? {
        val access = entry.body.cloud_access ?: return null
        if (entry.author != owner.deviceId || access.generation != owner.generation || access.storage_id != owner.storageId) return null
        return open(groupId, owner, access, ring)
    }

    private fun open(groupId: GroupId, owner: StorageOwnerState, access: CloudAccess, ring: Map<Int, ByteArray>): StorageAccess? {
        val groupKey = ring[access.key_epoch] ?: return null
        val storageId = owner.storageId.toByteArray()
        val grant = try {
            val plain = Primitives.chachaOpen(
                GroupKey.accessKey(groupKey), access.nonce.toByteArray(),
                aad(groupId, owner.generation, storageId, access.key_epoch), access.ciphertext.toByteArray(),
            )
            CloudGrant.ADAPTER.decode(plain)
        } catch (e: CryptoException) {
            return null
        } catch (e: IOException) {
            return null
        }
        return StorageAccess(owner.deviceId, storageId, owner.generation, grant.access_token, grant.expires_at_ms)
    }

    /**
     * [current] over the owner's log only, paged: the app never decodes the whole database for it (on Room every live
     * entry would be decoded). Null as [current].
     */
    suspend fun current(store: EntryStore, membership: MembershipState, ring: Map<Int, ByteArray>, page: Int = PAGE): StorageAccess? {
        val newest = newestAccessEntry(store, membership, page)
        return current(membership, listOfNotNull(newest), ring)
    }

    private const val PAGE = 500

    /** The owner's newest CLOUD_ACCESS entry, paging through the owner's log only; null without an owner or an entry. */
    internal suspend fun newestAccessEntry(store: EntryStore, membership: MembershipState, page: Int = PAGE): DecodedEntry? {
        val owner = membership.storageOwner ?: return null
        var newest: DecodedEntry? = null
        var after = 0L
        while (true) {
            val entries = store.entriesAfter(owner.deviceId, after, page)
            if (entries.isEmpty()) break
            entries.lastOrNull { it.kind == EntryKind.CLOUD_ACCESS }?.let { newest = it }
            after = entries.last().seq
        }
        return newest
    }

    private fun aad(groupId: GroupId, generation: Long, storageId: ByteArray, keyEpoch: Int): ByteArray =
        groupId.bytes() + ByteBuffer.allocate(12).putLong(generation).putInt(keyEpoch).array() + storageId
}
