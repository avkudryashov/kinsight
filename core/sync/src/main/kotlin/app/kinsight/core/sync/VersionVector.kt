package app.kinsight.core.sync

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.VectorItem
import okio.ByteString.Companion.toByteString

class VersionVector(val entries: Map<DeviceId, Long>) {
    operator fun get(id: DeviceId): Long = entries[id] ?: 0L

    fun shortHash(): ByteArray {
        val canonical = entries.toSortedMap().entries.joinToString(";") { "${it.key.hex}:${it.value}" }
        return Primitives.sha256(canonical.encodeToByteArray()).copyOf(4)
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
