package app.kinsight.core.model

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.model.proto.EntryBody
import app.kinsight.core.model.proto.SignedEntry
import okio.ByteString.Companion.toByteString

class EntryAuthor(
    val groupId: GroupId,
    val keys: DeviceKeys,
    lastSeq: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var lastSeq: Long = lastSeq
        private set

    fun create(fill: (EntryBody) -> EntryBody): DecodedEntry {
        val base = EntryBody(
            group_id = groupId.bytes().toByteString(),
            author_id = keys.deviceId.bytes().toByteString(),
            seq = lastSeq + 1,
            created_at_ms = clock(),
        )
        val body = fill(base)
        require(body.seq == base.seq && body.author_id == base.author_id && body.group_id == base.group_id) {
            "fill must not change the entry header"
        }
        val bodyBytes = EntryBody.ADAPTER.encode(body)
        val signed = SignedEntry(
            body = bodyBytes.toByteString(),
            signature = Ed25519.sign(keys.sign.privateKey, bodyBytes).toByteString(),
        )
        lastSeq = base.seq
        return DecodedEntry.decode(SignedEntry.ADAPTER.encode(signed))
    }
}
