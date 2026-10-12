package app.kinsight.core.keystore

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) and X25519 (OID 1.3.101.110): a fixed 12-byte
 * header followed by the 32-byte raw key (RFC 8410). Android Keystore hands out public keys in this form.
 */
object Spki {
    private val ED25519_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val X25519_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    fun ed25519Raw(encoded: ByteArray): ByteArray = raw(encoded, ED25519_PREFIX, "Ed25519")

    fun x25519Raw(encoded: ByteArray): ByteArray = raw(encoded, X25519_PREFIX, "X25519")

    /** A JCA public key for a raw X25519 key, as the platform KeyAgreement needs it for the peer side. */
    fun x25519PublicKey(raw: ByteArray): PublicKey {
        require(raw.size == 32) { "X25519 public key must be 32 bytes" }
        return KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_PREFIX + raw))
    }

    private fun raw(encoded: ByteArray, prefix: ByteArray, name: String): ByteArray {
        require(encoded.size == prefix.size + 32 && encoded.copyOf(prefix.size).contentEquals(prefix)) {
            "not an $name SubjectPublicKeyInfo"
        }
        return encoded.copyOfRange(prefix.size, encoded.size)
    }
}
