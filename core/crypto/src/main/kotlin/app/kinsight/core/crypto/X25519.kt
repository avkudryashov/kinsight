package app.kinsight.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

class X25519KeyPair(val privateKey: ByteArray, override val publicKey: ByteArray) : DhKey {
    override fun agree(peerPublicKey: ByteArray): ByteArray = X25519.agree(privateKey, peerPublicKey)
}

object X25519 {
    fun generate(random: SecureRandom = SecureRandom()): X25519KeyPair =
        fromPrivate(Primitives.randomBytes(32, random))

    fun fromPrivate(privateKey: ByteArray): X25519KeyPair {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return X25519KeyPair(privateKey.copyOf(), priv.generatePublicKey().encoded)
    }

    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != 32) throw CryptoException("bad X25519 public key length")
        val out = ByteArray(32)
        try {
            val a = X25519Agreement()
            a.init(X25519PrivateKeyParameters(privateKey, 0))
            a.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw CryptoException("X25519 agreement failed", e)
        }
        if (out.all { it == 0.toByte() }) throw CryptoException("low-order X25519 point")
        return out
    }
}
