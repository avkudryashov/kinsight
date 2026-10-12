package app.kinsight.core.crypto

/** An Ed25519 signing key whose private half may be non-exportable (Android Keystore). */
interface Signer {
    /** Raw 32-byte Ed25519 public key. */
    val publicKey: ByteArray

    /** Pure Ed25519 (RFC 8032) signature over [message], 64 bytes. Throws [CryptoException] if the backend fails. */
    fun sign(message: ByteArray): ByteArray
}

/** A static X25519 key whose private half may be non-exportable (Android Keystore). */
interface DhKey {
    /** Raw 32-byte X25519 public key. */
    val publicKey: ByteArray

    /**
     * Raw 32-byte X25519 shared secret with [peerPublicKey]. Throws [CryptoException] when the peer key is not
     * 32 bytes or the result is all zeros (low-order point), and when the backend fails.
     */
    fun agree(peerPublicKey: ByteArray): ByteArray
}
