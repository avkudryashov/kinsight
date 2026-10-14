package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.Hpke
import app.kinsight.core.crypto.HpkeSealed
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RotateDiscoveryKey
import app.kinsight.core.model.proto.SealedKey
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer

/**
 * The group key and its generations (spec §5). Generation 0 is the key every member received at pairing
 * (GroupConfig.discoveryKey); each later one comes from an admin's `MembershipOp.rotate`, sealed to the members active
 * at that moment, so a device that left never learns it. Membership keeps the newest rotation (highest epoch, replay
 * order breaks a tie). Bluetooth keeps using generation 0 for its advert tags; cloud keys are derived with own labels.
 */
object GroupKey {
    private val SEAL_INFO = "kinsight-group-key-v1".encodeToByteArray()
    private val PACK_LABEL = "kinsight-pack-key-v1".encodeToByteArray()
    private val ACCESS_LABEL = "kinsight-access-key-v1".encodeToByteArray()

    fun currentEpoch(membership: MembershipState): Int = membership.discoveryRotation?.epoch ?: 0

    /** A fresh key for the next generation, sealed to every active member. Only an admin's op takes effect. */
    fun rotate(author: EntryAuthor, membership: MembershipState, newKey: ByteArray = Primitives.randomBytes(32)): DecodedEntry {
        require(newKey.size == 32) { "group key must be 32 bytes" }
        val epoch = currentEpoch(membership) + 1
        val sealed = membership.active.values.sortedBy { it.id }.map { m ->
            val s = Hpke.seal(m.dhPublicKey.toByteArray(), SEAL_INFO, aad(membership.groupId, epoch, m.id), newKey)
            SealedKey(m.id.bytes().toByteString(), s.enc.toByteString(), s.ciphertext.toByteString())
        }
        return author.create { it.copy(membership_op = MembershipOp(rotate = RotateDiscoveryKey(epoch = epoch, keys = sealed))) }
    }

    /** This device's key of the newest generation, or null when there is none or it was not sealed to this device. */
    fun open(keys: DeviceKeys, membership: MembershipState): Pair<Int, ByteArray>? {
        val r = membership.discoveryRotation ?: return null
        val self = keys.deviceId.bytes().toByteString()
        val mine = r.keys.firstOrNull { it.device_id == self } ?: return null
        val key = try {
            Hpke.open(keys.dh, SEAL_INFO, aad(membership.groupId, r.epoch, keys.deviceId), HpkeSealed(mine.enc.toByteArray(), mine.ciphertext.toByteArray()))
        } catch (e: CryptoException) {
            return null
        }
        return if (key.size == 32) r.epoch to key else null
    }

    /** Spec §5: the key changes when any device leaves. True while a departed device holds the newest generation. */
    fun rotationNeeded(membership: MembershipState): Boolean {
        val r = membership.discoveryRotation ?: return membership.departed.isNotEmpty()
        val holders = r.keys.map { it.device_id }.toSet()
        return membership.departed.keys.any { it.bytes().toByteString() in holders }
    }

    /**
     * Tells two keys of one generation apart (two admins rotating at once each create one; Membership keeps one of them).
     * The first 8 bytes of SHA-256, hex; kept only in the local cursor.
     */
    fun fingerprint(groupKey: ByteArray): String = Primitives.sha256(groupKey).copyOf(8).toHex()

    fun packKey(groupKey: ByteArray): ByteArray = Primitives.hmacSha256(groupKey, PACK_LABEL)

    fun accessKey(groupKey: ByteArray): ByteArray = Primitives.hmacSha256(groupKey, ACCESS_LABEL)

    private fun aad(groupId: GroupId, epoch: Int, device: DeviceId): ByteArray =
        groupId.bytes() + ByteBuffer.allocate(4).putInt(epoch).array() + device.bytes()

    /**
     * Active members the newest rotation was not sealed to although their AddMember carries a lower Lamport value than the
     * rotation op: its author cannot have seen the rotation, so the member got an older generation at pairing and never
     * gets this one (Y1 review). Turning the cloud on, and any admin's cloud run, rotates again while this is not empty. A
     * member added after the rotation got the newest generation in its welcome and is not listed.
     */
    fun membersWithoutKey(membership: MembershipState): Set<DeviceId> {
        val r = membership.discoveryRotation ?: return emptySet()
        val op = membership.effectiveEntries.firstOrNull { it.body.membership_op?.rotate == r } ?: return emptySet()
        val holders = r.keys.map { it.device_id }.toSet()
        val addedAt = membership.effectiveEntries.mapNotNull { e ->
            val pk = e.body.membership_op?.add?.sign_public_key ?: return@mapNotNull null
            DeviceId.fromSignPublicKey(pk.toByteArray()) to e.lamport
        }.toMap()
        return membership.active.keys.filter { id ->
            id.bytes().toByteString() !in holders && (addedAt[id] ?: 0L) < op.lamport
        }.toSet()
    }
}
