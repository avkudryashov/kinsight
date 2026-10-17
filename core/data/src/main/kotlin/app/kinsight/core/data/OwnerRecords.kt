package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.transport.cloud.CloudAccessRefused
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.GroupKeyRing

/** What [OwnerRecords.claim] signed, and the key generation its CloudAccess is sealed with (the «переезд» pack uses it). */
internal class Claimed(val entries: List<DecodedEntry>, val epoch: Int, val groupKey: ByteArray)

/**
 * The owner's signed records (spec §4, §6, §7): StorageOwner and CloudAccess («новый доступ»), always sealed with the
 * generation CloudSync seals with, after the newest rotation sealed to this phone joined the ring.
 */
internal object OwnerRecords {
    /** Rulings Q3: a fresh sign-in's token id. */
    const val TOKEN_ID_BYTES = 16

    /**
     * The generation CloudSync seals with: the group's newest when held, else the newest held. The newest rotation sealed
     * to this phone joins the ring first, so a rotation signed while the cloud was off (a removal) is never skipped.
     */
    suspend fun writeKey(keys: DeviceKeys, m: MembershipState, ring: GroupKeyRing): Pair<Int, ByteArray> {
        GroupKey.open(keys, m)?.let { (epoch, key) -> if (ring.keys()[epoch]?.contentEquals(key) != true) ring.put(epoch, key) }
        val held = ring.keys()
        val current = GroupKey.currentEpoch(m)
        val epoch = if (current in held) current else held.keys.max()
        return epoch to held.getValue(epoch)
    }

    /**
     * StorageOwner(this phone, [storageId], [generation]) and its CloudAccess with [accessToken], signed together in one
     * append. Null when nothing was signed: this phone is not an active admin parent, or [generation] is not above the
     * current one. Rulings Q3: the op names the token ([tokenId]: null for a fresh sign-in, which gets a new random id;
     * the id of the token in force when the same token goes on, spec §7.3) and the departures it was issued after
     * ([departed]: null for every departure this phone knows of; when the same token goes on, the op in force's list,
     * copied unchanged).
     */
    suspend fun claim(
        appendOwn: OwnAppend,
        store: EntryStore,
        keys: DeviceKeys,
        ring: GroupKeyRing,
        storageId: ByteArray,
        generation: Long,
        accessToken: String,
        expiresAtMs: Long,
        tokenId: ByteArray? = null,
        departed: Collection<DeviceId>? = null,
    ): Claimed? {
        val entries = store.membershipEntries()
        val m = Membership.compute(store.groupId, entries) ?: return null
        val me = m.active[keys.deviceId]
        if (me == null || !me.isAdmin || me.isChild || generation <= (m.storageOwner?.generation ?: 0L)) return null
        val (epoch, key) = writeKey(keys, m, ring)
        val id = tokenId ?: Primitives.randomBytes(TOKEN_ID_BYTES)
        // Rulings Q16: over 256 lifetime departures the rest stay "exposed" (one renewal prompt per check), out of scope.
        val known = departed?.distinct()?.sortedBy { it.hex }?.take(MembershipLimits.MAX_OWNER_DEPARTED)
            ?: m.departed.keys.sortedBy { it.hex }.take(MembershipLimits.MAX_OWNER_DEPARTED)
        var signed: List<DecodedEntry> = emptyList()
        val ok = refusedAsFalse {
            appendOwn { a ->
                val ownerEntry = a.storageOwnerEntry(keys.deviceId, storageId, generation, id, known)
                val withOwner = Membership.compute(store.groupId, entries + ownerEntry)!!
                listOf(ownerEntry, CloudAccessView.issue(a, withOwner, epoch, key, accessToken, expiresAtMs)).also { signed = it }
            }
        }
        return if (ok && signed.isNotEmpty()) Claimed(signed, epoch, key) else null
    }

    /** A new CloudAccess of the current storage (spec §6, §7.4 «новый доступ»): the owner only. */
    suspend fun issue(appendOwn: OwnAppend, keys: DeviceKeys, m: MembershipState, ring: GroupKeyRing, accessToken: String, expiresAtMs: Long): Boolean {
        val (epoch, key) = writeKey(keys, m, ring)
        return refusedAsFalse { appendOwn { a -> listOf(CloudAccessView.issue(a, m, epoch, key, accessToken, expiresAtMs)) } }
    }

    /**
     * A CloudAccess the receivers' rules would refuse (CloudAccessView.issue throws [CloudAccessRefused] before signing,
     * e.g. a token of kilobytes) is «not signed», like every other refusal, and logged (its reason names no token): the
     * caller's existing outcome applies and a cloud run goes on. Any other fault is not swallowed.
     */
    private inline fun refusedAsFalse(append: () -> Boolean): Boolean = try {
        append()
    } catch (e: CloudAccessRefused) {
        Log.w(TAG, "not signed: ${e.message}")
        false
    }

    private const val TAG = "KinsightCloud"
}
