package app.kinsight

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no Android Keystore. This stands in for the part a first run on a phone without a usable Keystore
 * needs: an alias store and AES keys (the software backend's wrapping key). It offers no EC key pairs, so the key
 * selector falls back to software keys, as it does on such a phone.
 */
object FakeAndroidKeyStore {
    private val keys = ConcurrentHashMap<String, Key>()

    fun install() {
        if (Security.getProvider(NAME) == null) Security.insertProviderAt(KeystoreProvider(), 1)
    }

    private class KeystoreProvider : Provider(NAME, 1.0, "fake Android Keystore for JVM tests") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", AesGenerator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class AesGenerator : KeyGeneratorSpi() {
        private var alias: String? = null
        private var bits = 256

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as KeyGenParameterSpec
            alias = spec.keystoreAlias
            if (spec.keySize > 0) bits = spec.keySize
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            bits = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(bits / 8).also(SecureRandom()::nextBytes), "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }

    private const val NAME = "AndroidKeyStore"
}
