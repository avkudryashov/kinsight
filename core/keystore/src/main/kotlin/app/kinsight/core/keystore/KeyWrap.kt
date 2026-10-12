package app.kinsight.core.keystore

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for small secrets at rest. */
interface KeyWrap {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Throws when [sealed] was not produced by this wrap with the same [aad]. */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a fresh IV chosen by the cipher (Android Keystore keys refuse caller-supplied IVs).
 * Output: iv (12 bytes) followed by ciphertext and 16-byte tag.
 */
class AesGcmWrap(private val key: () -> SecretKey) : KeyWrap {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        if (iv.size != IV_LEN) throw GeneralSecurityException("unexpected GCM IV length ${iv.size}")
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < IV_LEN + TAG_LEN) throw GeneralSecurityException("sealed blob too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LEN * 8, sealed, 0, IV_LEN))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_LEN = 16
    }
}
