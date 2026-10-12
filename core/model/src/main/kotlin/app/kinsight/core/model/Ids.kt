package app.kinsight.core.model

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.hexToBytes
import app.kinsight.core.crypto.toHex

@JvmInline
value class DeviceId(val hex: String) : Comparable<DeviceId> {
    init {
        require(hex.length == 32) { "DeviceId must be 16 bytes" }
    }

    fun bytes(): ByteArray = hex.hexToBytes()

    override fun compareTo(other: DeviceId): Int = hex.compareTo(other.hex)

    companion object {
        fun fromSignPublicKey(signPublicKey: ByteArray) =
            DeviceId(Primitives.sha256(signPublicKey).copyOf(16).toHex())

        fun fromBytes(bytes: ByteArray) = DeviceId(bytes.toHex())
    }
}

@JvmInline
value class GroupId(val hex: String) {
    init {
        require(hex.length == 32) { "GroupId must be 16 bytes" }
    }

    fun bytes(): ByteArray = hex.hexToBytes()

    companion object {
        fun derive(creatorSignPublicKey: ByteArray, salt: ByteArray) =
            GroupId(Primitives.sha256(creatorSignPublicKey, salt).copyOf(16).toHex())

        fun fromBytes(bytes: ByteArray) = GroupId(bytes.toHex())
    }
}
