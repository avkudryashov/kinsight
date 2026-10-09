package app.kinsight.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

object Ed25519 {
    fun generate(random: SecureRandom = SecureRandom()): Ed25519KeyPair =
        fromSeed(Primitives.randomBytes(32, random))

    fun fromSeed(seed: ByteArray): Ed25519KeyPair {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        return Ed25519KeyPair(seed.copyOf(), priv.generatePublicKey().encoded)
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
