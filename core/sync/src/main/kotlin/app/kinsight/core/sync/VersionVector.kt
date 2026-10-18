package app.kinsight.core.sync

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.VectorItem
import okio.ByteString.Companion.toByteString

class VersionVector(val entries: Map<DeviceId, Long>) {
    operator fun get(id: DeviceId): Long = entries[id] ?: 0L

    /** This vector with [id] at [seq]; zero leaves [id] out, as a store's vector does. */
    fun with(id: DeviceId, seq: Long): VersionVector = VersionVector(if (seq > 0) entries + (id to seq) else entries - id)

    /** Authors by DeviceId order, each as its 16 bytes plus its seq (8 B big-endian): input of the advert vector tag. */
    fun canonicalBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream(entries.size * 24)
        for ((id, seq) in entries.toSortedMap()) {
            out.write(id.bytes())
            for (i in 7 downTo 0) out.write((seq ushr (8 * i)).toInt() and 0xff)
        }
        return out.toByteArray()
    }

    fun toProto(): List<VectorItem> =
        entries.toSortedMap().map { (id, seq) -> VectorItem(author_id = id.bytes().toByteString(), seq = seq) }

    override fun equals(other: Any?) = other is VersionVector && entries == other.entries
    override fun hashCode() = entries.hashCode()
    override fun toString() = entries.entries.joinToString(prefix = "VV(", postfix = ")") { "${it.key.hex.take(6)}=${it.value}" }

    companion object {
        val EMPTY = VersionVector(emptyMap())

        fun fromProto(items: List<VectorItem>): VersionVector =
            VersionVector(
                items.filter { it.author_id.size == 16 }
                    .associate { DeviceId.fromBytes(it.author_id.toByteArray()) to it.seq },
            )
    }
}
