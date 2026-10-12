package app.kinsight.core.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Signer
import app.kinsight.core.model.DeviceKeys
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Non-exportable Ed25519 and X25519 keys in Android Keystore (TEE; StrongBox lacks these curves). Needs
 * KeyMint with Curve25519 (Android 13+ in practice); on older devices [create] throws and the selector falls
 * back to software keys. No user authentication: background sync runs while the phone is locked.
 */
class KeystoreKeys(
    private val signAlias: String = SIGN_ALIAS,
    private val dhAlias: String = DH_ALIAS,
) : KeyBackend {
    override val kind = KeyBackendKind.KEYSTORE

    /** Replaces any keys (including a partial pair left by an interrupted create) under both aliases. */
    override fun create(): DeviceKeys {
        try {
            delete()
            generate(signAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, "ed25519") {
                setDigests(KeyProperties.DIGEST_NONE)
            }
            generate(dhAlias, KeyProperties.PURPOSE_AGREE_KEY, "x25519") {}
        } catch (e: Exception) {
            runCatching { delete() }
            throw KeyStorageException("Android Keystore cannot create Curve25519 keys", e)
        }
        return load()
    }

    override fun load(): DeviceKeys = try {
        val ks = keyStore()
        DeviceKeys(
            sign = KeystoreSigner(privateKey(ks, signAlias), Spki.ed25519Raw(publicKeyEncoded(ks, signAlias))),
            dh = KeystoreDhKey(privateKey(ks, dhAlias), Spki.x25519Raw(publicKeyEncoded(ks, dhAlias))),
        )
    } catch (e: KeyStorageException) {
        throw e
    } catch (e: Exception) {
        throw KeyStorageException("Android Keystore keys are unreadable", e)
    }

    /** True when the signing or DH alias exists; the software backend's wrap alias never counts. */
    override fun hasKeys(): Boolean = try {
        val ks = keyStore()
        ks.containsAlias(signAlias) || ks.containsAlias(dhAlias)
    } catch (e: Exception) {
        throw KeyStorageException("cannot query Android Keystore aliases", e)
    }

    /** Deletes the signing and DH aliases only; the wrap key of the software backend stays. */
    override fun delete() = try {
        val ks = keyStore()
        ks.deleteEntry(signAlias)
        ks.deleteEntry(dhAlias)
    } catch (e: Exception) {
        throw KeyStorageException("cannot delete Android Keystore keys", e)
    }

    /** KeyInfo.securityLevel of the signing and DH keys, for diagnostics and tests. */
    fun securityLevels(): List<Int> {
        val ks = keyStore()
        return listOf(signAlias, dhAlias).map { alias ->
            val key = privateKey(ks, alias)
            // Curve25519 private keys report an OID as their algorithm; the EC factory they were generated with reads them.
            KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java).securityLevel
        }
    }

    private fun generate(alias: String, purposes: Int, curve: String, extra: KeyGenParameterSpec.Builder.() -> Unit) {
        val spec = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec(curve))
            .apply(extra)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private fun privateKey(ks: KeyStore, alias: String): PrivateKey =
        ks.getKey(alias, null) as? PrivateKey ?: throw KeyStorageException("Keystore key $alias is missing")

    private fun publicKeyEncoded(ks: KeyStore, alias: String): ByteArray =
        ks.getCertificate(alias)?.publicKey?.encoded ?: throw KeyStorageException("Keystore certificate $alias is missing")

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGN_ALIAS = "kinsight.sign.v1"
        const val DH_ALIAS = "kinsight.dh.v1"

        internal fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
}

private class KeystoreSigner(private val key: PrivateKey, override val publicKey: ByteArray) : Signer {
    override fun sign(message: ByteArray): ByteArray = try {
        Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        }
    } catch (e: Exception) {
        throw CryptoException("Keystore Ed25519 signing failed", e)
    }
}

private class KeystoreDhKey(private val key: PrivateKey, override val publicKey: ByteArray) : DhKey {
    override fun agree(peerPublicKey: ByteArray): ByteArray {
        if (peerPublicKey.size != 32) throw CryptoException("bad X25519 public key length")
        val secret = try {
            KeyAgreement.getInstance("XDH").run {
                init(key)
                // The platform agreement needs a JCA key for the peer: re-import the raw bytes as X.509.
                doPhase(Spki.x25519PublicKey(peerPublicKey), true)
                generateSecret()
            }
        } catch (e: Exception) {
            // ProviderException, InvalidKeyException, KeyStore errors, and a low-order point refused by KeyMint.
            throw CryptoException("Keystore X25519 agreement failed", e)
        }
        if (secret.size != 32 || secret.all { it == 0.toByte() }) throw CryptoException("low-order X25519 point")
        return secret
    }
}
