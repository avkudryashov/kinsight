package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.PackBody
import app.kinsight.core.model.proto.PackFile
import app.kinsight.core.model.proto.PackHeader
import okio.ByteString.Companion.toByteString
import java.io.IOException

class OpenedPack(val author: DeviceId, val from: Long, val to: Long, val epoch: Int, val entries: List<ByteArray>)

sealed interface PackOutcome {
    class Opened(val pack: OpenedPack) : PackOutcome

    /** Signed by a device this replica does not know yet: try again after other packs brought its AddMember. */
    class UnknownAuthor(val author: DeviceId) : PackOutcome

    /** Sealed with a key generation this device does not hold (it left before it, or joined after and the author has not re-encrypted yet). */
    class KeyUnknown(val epoch: Int) : PackOutcome

    class Rejected(val reason: String) : PackOutcome
}

/**
 * One pack file (spec §5, cloud.proto): the author's SignedEntry records sealed with the group key of [PackHeader.key_epoch]
 * (ChaCha20-Poly1305 under GroupKey.packKey, the header as AAD). The header is signed by the author.
 */
object PackCodec {
    private val SIGN_CONTEXT = "kinsight-pack-v1".encodeToByteArray()

    fun seal(
        keys: DeviceKeys,
        groupId: GroupId,
        entries: List<DecodedEntry>,
        from: Long,
        to: Long,
        epoch: Int,
        groupKey: ByteArray,
        nowMs: Long,
        keyUpdates: List<DecodedEntry> = emptyList(),
    ): ByteArray {
        require(from in 1..to) { "bad range $from..$to" }
        require(keyUpdates.all { it.body.membership_op?.rotate != null }) { "key updates are rotation ops only" }
        require(entries.all { it.author == keys.deviceId && it.seq in from..to }) { "a pack carries its author's own range only" }
        val body = PackBody.ADAPTER.encode(PackBody(entries = entries.map { it.bytes.toByteString() }))
        val header = PackHeader.ADAPTER.encode(
            PackHeader(
                group_id = groupId.bytes().toByteString(),
                author_id = keys.deviceId.bytes().toByteString(),
                from_seq = from,
                to_seq = to,
                body_hash = Primitives.sha256(body).toByteString(),
                created_at_ms = nowMs,
                key_epoch = epoch,
            ),
        )
        val nonce = Primitives.randomBytes(12)
        return PackFile.ADAPTER.encode(
            PackFile(
                header_ = header.toByteString(),
                signature = keys.sign.sign(SIGN_CONTEXT + header).toByteString(),
                nonce = nonce.toByteString(),
                ciphertext = Primitives.chachaSeal(GroupKey.packKey(groupKey), nonce, header, body).toByteString(),
                key_updates = keyUpdates.map { it.bytes.toByteString() },
            ),
        )
    }

    /** [folder]: the device folder the file was found in; a pack signed by anyone else is rejected. */
    fun open(file: ByteArray, ring: Map<Int, ByteArray>, groupId: GroupId, folder: DeviceId, membership: MembershipState): PackOutcome {
        val pf = try {
            PackFile.ADAPTER.decode(file)
        } catch (e: IOException) {
            return PackOutcome.Rejected("undecodable file")
        }
        val headerBytes = pf.header_.toByteArray()
        val header = try {
            PackHeader.ADAPTER.decode(headerBytes)
        } catch (e: IOException) {
            return PackOutcome.Rejected("undecodable header")
        }
        if (header.group_id != groupId.bytes().toByteString()) return PackOutcome.Rejected("foreign group")
        if (header.author_id.size != 16) return PackOutcome.Rejected("bad author id")
        val author = DeviceId.fromBytes(header.author_id.toByteArray())
        if (author != folder) return PackOutcome.Rejected("pack of ${author.hex} in the folder of ${folder.hex}")
        if (header.from_seq < 1 || header.to_seq < header.from_seq) return PackOutcome.Rejected("bad range")
        val signKey = membership.signKeyOf(author) ?: return PackOutcome.UnknownAuthor(author)
        if (!Ed25519.verify(signKey, SIGN_CONTEXT + headerBytes, pf.signature.toByteArray())) return PackOutcome.Rejected("bad signature")
        val groupKey = ring[header.key_epoch] ?: return PackOutcome.KeyUnknown(header.key_epoch)
        if (pf.nonce.size != 12) return PackOutcome.Rejected("bad nonce")
        val body = try {
            Primitives.chachaOpen(GroupKey.packKey(groupKey), pf.nonce.toByteArray(), headerBytes, pf.ciphertext.toByteArray())
        } catch (e: CryptoException) {
            return PackOutcome.Rejected("ciphertext does not open")
        }
        if (!Primitives.sha256(body).contentEquals(header.body_hash.toByteArray())) return PackOutcome.Rejected("body hash")
        val entries = try {
            PackBody.ADAPTER.decode(body).entries.map { it.toByteArray() }
        } catch (e: IOException) {
            return PackOutcome.Rejected("undecodable body")
        }
        return PackOutcome.Opened(OpenedPack(author, header.from_seq, header.to_seq, header.key_epoch, entries))
    }

    /**
     * The rotation ops the author put in clear next to the body: a reader that lacks the pack's generation learns it from
     * them (its own sealed copy inside), as the rotation op itself sits in an author's pack sealed with the new key.
     */
    fun keyUpdates(file: ByteArray): List<DecodedEntry> = try {
        PackFile.ADAPTER.decode(file).key_updates.mapNotNull { raw ->
            runCatching { DecodedEntry.decode(raw.toByteArray()) }.getOrNull()?.takeIf { it.body.membership_op?.rotate != null }
        }
    } catch (e: IOException) {
        emptyList()
    }

    /** The key generation of a pack (compaction and tests); null for an undecodable file. */
    fun epochOf(file: ByteArray): Int? = try {
        PackHeader.ADAPTER.decode(PackFile.ADAPTER.decode(file).header_).key_epoch
    } catch (e: IOException) {
        null
    }
}
