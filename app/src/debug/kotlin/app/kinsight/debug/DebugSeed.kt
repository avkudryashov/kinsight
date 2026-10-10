package app.kinsight.debug

import app.kinsight.core.crypto.hexToBytes
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.GroupId
import app.kinsight.core.transport.ble.GroupConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Debug builds only: what a phone needs to join a test group made over adb (stage 4 QR pairing replaces it).
 * Hex of: version (1), groupId (16 B), discovery key (32 B), entry count (u16), then per entry its length (u16) and
 * its SignedEntry bytes.
 */
class DebugSeed(val config: GroupConfig, val membership: List<ByteArray>) {
    fun encode(): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(VERSION)
            data.write(config.groupId.bytes())
            data.write(config.discoveryKey)
            data.writeShort(membership.size)
            for (e in membership) {
                data.writeShort(e.size)
                data.write(e)
            }
        }
        return out.toByteArray().toHex()
    }

    companion object {
        private const val VERSION = 1

        /** Any malformed seed (bad hex, unknown version, truncated, trailing bytes) is an IllegalArgumentException. */
        fun decode(hex: String): DebugSeed {
            val input = DataInputStream(hex.trim().hexToBytes().inputStream())
            try {
                require(input.readUnsignedByte() == VERSION) { "unknown seed version" }
                val group = ByteArray(16).also { input.readFully(it) }
                val key = ByteArray(32).also { input.readFully(it) }
                val entries = List(input.readUnsignedShort()) { ByteArray(input.readUnsignedShort()).also { input.readFully(it) } }
                require(input.read() == -1) { "trailing bytes in seed" }
                return DebugSeed(GroupConfig(GroupId.fromBytes(group), key), entries)
            } catch (e: EOFException) {
                throw IllegalArgumentException("truncated seed", e)
            }
        }
    }
}

/** `<sign public key hex>:<dh public key hex>:<display name>`; IDENTITY prints the first two fields. */
class MemberSpec(val sign: ByteArray, val dh: ByteArray, val name: String) {
    companion object {
        fun parse(s: String): MemberSpec {
            val parts = s.split(':', limit = 3)
            require(parts.size == 3) { "member must be sign:dh:name" }
            val sign = parts[0].hexToBytes()
            val dh = parts[1].hexToBytes()
            require(sign.size == 32 && dh.size == 32) { "public keys must be 32 bytes each" }
            return MemberSpec(sign, dh, parts[2])
        }
    }
}
