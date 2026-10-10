package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.PROTOCOL_VERSION
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

open class InvalidInviteException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UnsupportedInviteVersionException(message: String) : InvalidInviteException(message)

/**
 * The QR content of an invitation (spec §6.4): `kinsight://pair/1/<base64url>` over
 * format (1) | protocol version (u16 BE) | groupId (16) | admin Ed25519 key (32) | admin X25519 key (32) |
 * token (16) | role (1) | name length (1) | admin name (UTF-8, at most [MAX_NAME_BYTES]).
 * The token lives [LIFETIME_MS] on the admin's side; the invite itself carries no expiry. [parse] refuses text over
 * [MAX_TEXT_CHARS], a blank admin name or one with control/bidi characters, and throws [UnsupportedInviteVersionException]
 * for another `kinsight://pair/N/` format or a protocol version other than PROTOCOL_VERSION.
 */
class Invite(
    val protocolVersion: Int,
    val groupId: GroupId,
    val adminSignKey: ByteArray,
    val adminDhKey: ByteArray,
    val token: ByteArray,
    val role: DeviceRole,
    val adminName: String,
) {
    init {
        require(protocolVersion in 0..0xffff) { "bad protocol version $protocolVersion" }
        require(adminSignKey.size == 32 && adminDhKey.size == 32) { "admin keys must be 32 bytes" }
        require(token.size == TOKEN_BYTES) { "token must be $TOKEN_BYTES bytes" }
        require(role == DeviceRole.PARENT || role == DeviceRole.CHILD) { "invite role must be PARENT or CHILD" }
        require(adminName.encodeToByteArray().size <= MAX_NAME_BYTES) { "admin name over $MAX_NAME_BYTES bytes" }
        require(adminName.isNotBlank() && !PairingNames.hasUnsafe(adminName)) { "admin name blank or with control/bidi characters" }
    }

    val adminId: DeviceId get() = DeviceId.fromSignPublicKey(adminSignKey)

    /** What the admin advertises while the invite is open; derived from the token, so only QR holders find it. */
    val advertTag: ByteArray get() = Primitives.hmacSha256(token, ADVERT_LABEL).copyOf(ADVERT_TAG_BYTES)

    fun encode(): ByteArray {
        val name = adminName.encodeToByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(FORMAT)
            d.writeShort(protocolVersion)
            d.write(groupId.bytes())
            d.write(adminSignKey)
            d.write(adminDhKey)
            d.write(token)
            d.writeByte(role.value)
            d.writeByte(name.size)
            d.write(name)
        }
        return out.toByteArray()
    }

    fun toUri(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

    companion object {
        const val PREFIX = "kinsight://pair/1/"
        private const val FAMILY_PREFIX = "kinsight://pair/"
        const val FORMAT = 1
        const val TOKEN_BYTES = 16
        const val ADVERT_TAG_BYTES = 8
        const val MAX_NAME_BYTES = 60
        const val LIFETIME_MS = 10 * 60_000L
        const val MAX_TEXT_CHARS = 512
        private val ADVERT_LABEL = "kinsight-pair-advert".encodeToByteArray()

        fun create(groupId: GroupId, admin: DeviceKeys, role: DeviceRole, adminName: String): Invite = Invite(
            PROTOCOL_VERSION, groupId, admin.sign.publicKey, admin.dh.publicKey,
            Primitives.randomBytes(TOKEN_BYTES), role, truncateName(PairingNames.strip(adminName).trim()),
        )

        /** The longest prefix of [name] (whole code points) that fits [maxBytes] of UTF-8. */
        fun truncateName(name: String, maxBytes: Int = MAX_NAME_BYTES): String {
            val out = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val n = String(Character.toChars(cp)).encodeToByteArray().size
                if (bytes + n > maxBytes) break
                out.appendCodePoint(cp)
                bytes += n
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        fun parse(text: String): Invite {
            if (text.length > MAX_TEXT_CHARS) throw InvalidInviteException("invite text over $MAX_TEXT_CHARS characters")
            val t = text.trim()
            if (!t.startsWith(PREFIX)) {
                if (t.startsWith(FAMILY_PREFIX)) throw UnsupportedInviteVersionException("unsupported invite format: ${t.take(24)}")
                throw InvalidInviteException("not a Kinsight invite")
            }
            val bytes = try {
                Base64.getUrlDecoder().decode(t.removePrefix(PREFIX))
            } catch (e: IllegalArgumentException) {
                throw InvalidInviteException("bad base64", e)
            }
            return decode(bytes)
        }

        fun decode(bytes: ByteArray): Invite = try {
            val input = DataInputStream(bytes.inputStream())
            if (input.readUnsignedByte() != FORMAT) throw InvalidInviteException("unknown invite format")
            val protocol = input.readUnsignedShort()
            if (protocol != PROTOCOL_VERSION) throw UnsupportedInviteVersionException("protocol $protocol, this app speaks $PROTOCOL_VERSION")
            val group = ByteArray(16).also { input.readFully(it) }
            val sign = ByteArray(32).also { input.readFully(it) }
            val dh = ByteArray(32).also { input.readFully(it) }
            val token = ByteArray(TOKEN_BYTES).also { input.readFully(it) }
            val role = DeviceRole.fromValue(input.readUnsignedByte()) ?: throw InvalidInviteException("unknown role")
            val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (input.read() != -1) throw InvalidInviteException("trailing bytes")
            Invite(protocol, GroupId.fromBytes(group), sign, dh, token, role, name.decodeToString(throwOnInvalidSequence = true))
        } catch (e: InvalidInviteException) {
            throw e
        } catch (e: Exception) {
            throw InvalidInviteException("invalid invite: ${e.message}", e) // EOF, bad UTF-8, a require() above
        }
    }
}

/** Characters a display name may not carry: C0/C1 controls and DEL, and bidi embeddings, overrides, isolates and marks. */
internal object PairingNames {
    fun isUnsafe(cp: Int): Boolean =
        Character.getType(cp) == Character.CONTROL.toInt() ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C

    fun hasUnsafe(name: String): Boolean = name.codePoints().anyMatch(::isUnsafe)

    fun strip(name: String): String = buildString { name.codePoints().filter { !isUnsafe(it) }.forEach(::appendCodePoint) }
}
