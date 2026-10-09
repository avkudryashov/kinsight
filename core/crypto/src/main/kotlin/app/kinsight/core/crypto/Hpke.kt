package app.kinsight.core.crypto

import org.bouncycastle.crypto.hpke.HPKE

class HpkeSealed(val enc: ByteArray, val ciphertext: ByteArray)

object Hpke {
    private fun suite() = HPKE(
        HPKE.mode_base,
        HPKE.kem_X25519_SHA256,
        HPKE.kdf_HKDF_SHA256,
        HPKE.aead_CHACHA20_POLY1305,
    )

    fun seal(recipientPublicKey: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray): HpkeSealed {
        val hpke = suite()
        val result = hpke.seal(hpke.deserializePublicKey(recipientPublicKey), info, aad, plaintext, null, null, null)
        return HpkeSealed(enc = result[1], ciphertext = result[0])
    }

    fun open(recipient: X25519KeyPair, info: ByteArray, aad: ByteArray, sealed: HpkeSealed): ByteArray {
        val hpke = suite()
        return try {
            val keyPair = hpke.deserializePrivateKey(recipient.privateKey, recipient.publicKey)
            hpke.open(sealed.enc, keyPair, info, aad, sealed.ciphertext, null, null, null)
        } catch (e: Exception) {
            throw CryptoException("HPKE open failed", e)
        }
    }
}
