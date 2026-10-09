package app.kinsight.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Primitives {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        parts.forEach { d.update(it, 0, it.size) }
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        parts.forEach { mac.update(it, 0, it.size) }
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(ciphertext.size))
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, n)
            return out
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("AEAD authentication failed", e)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("AEAD input rejected", e)
        }
    }

    fun randomBytes(n: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(n).also(random::nextBytes)
}
