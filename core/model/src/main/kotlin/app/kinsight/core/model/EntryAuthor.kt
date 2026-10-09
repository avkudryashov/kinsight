package app.kinsight.core.model

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.model.proto.EntryBody
import app.kinsight.core.model.proto.SignedEntry
import okio.ByteString.Companion.toByteString

/**
 * Creates and signs the entries of one device. Each entry gets seq = previous + 1 and a Lamport value
 * max([lastLamport], [observedLamport]()) + 1, where [observedLamport] reports the highest Lamport value
 * this device has seen from anyone (typically the local store's max), so an entry orders after everything
 * its author had observed regardless of wall clocks. The value saturates at MembershipLimits.MAX_LAMPORT.
 * [observedLamport] should report only effective membership entries (MembershipState.maxLamport), so a
 * non-admin cannot pull everyone's clock up with an arbitrary entry.
 */
class EntryAuthor(
    val groupId: GroupId,
    val keys: DeviceKeys,
    lastSeq: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    lastLamport: Long = 0,
    private val observedLamport: () -> Long = { 0L },
) {
    var lastSeq: Long = lastSeq
        private set

    var lastLamport: Long = lastLamport
        private set

    fun create(fill: (EntryBody) -> EntryBody): DecodedEntry {
        val base = EntryBody(
            group_id = groupId.bytes().toByteString(),
            author_id = keys.deviceId.bytes().toByteString(),
            seq = lastSeq + 1,
            created_at_ms = clock(),
            lamport = minOf(maxOf(lastLamport, observedLamport()) + 1, MembershipLimits.MAX_LAMPORT).coerceAtLeast(1),
        )
        val body = fill(base)
        require(body.seq == base.seq && body.author_id == base.author_id && body.group_id == base.group_id) {
            "fill must not change the entry header"
        }
        val bodyBytes = EntryBody.ADAPTER.encode(body)
        val signature = keys.sign.sign(bodyBytes)
        // A faulty hardware key must not burn a seq on an entry no peer would accept.
        check(Ed25519.verify(keys.sign.publicKey, bodyBytes, signature)) { "signer produced an invalid signature" }
        val signed = SignedEntry(
            body = bodyBytes.toByteString(),
            signature = signature.toByteString(),
        )
        lastSeq = base.seq
        lastLamport = maxOf(base.lamport, body.lamport)
        return DecodedEntry.decode(SignedEntry.ADAPTER.encode(signed))
    }
}
