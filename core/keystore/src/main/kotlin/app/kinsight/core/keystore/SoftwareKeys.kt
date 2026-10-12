package app.kinsight.core.keystore

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import java.io.File
import java.security.SecureRandom

/**
 * Software Ed25519 + X25519 keys kept in [file], sealed by [wrap] (an Android Keystore AES key in the app).
 * File: "KSK1" followed by wrap.seal(ed25519 seed (32) || x25519 private key (32)).
 */
class SoftwareKeys(
    private val file: File,
    private val wrap: KeyWrap,
    private val random: SecureRandom = SecureRandom(),
) : KeyBackend {
    override val kind = KeyBackendKind.SOFTWARE

    override fun create(): DeviceKeys {
        val seed = Primitives.randomBytes(32, random)
        val dhPrivate = Primitives.randomBytes(32, random)
        val sealed = try {
            wrap.seal(seed + dhPrivate, AAD)
        } catch (e: Exception) {
            throw KeyStorageException("cannot seal software keys", e)
        }
        writeAtomically(file, MAGIC + sealed)
        return keysOf(seed, dhPrivate)
    }

    override fun load(): DeviceKeys {
        if (!file.exists()) throw KeyStorageException("software keys missing: ${file.name}")
        val blob = try {
            file.readBytes()
        } catch (e: Exception) {
            throw KeyStorageException("cannot read ${file.name}", e)
        }
        if (blob.size <= MAGIC.size || !blob.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw KeyStorageException("software key file has a bad header")
        }
        val plain = try {
            wrap.open(blob.copyOfRange(MAGIC.size, blob.size), AAD)
        } catch (e: Exception) {
            throw KeyStorageException("cannot unseal software keys", e)
        }
        if (plain.size != 64) throw KeyStorageException("software key payload has ${plain.size} bytes")
        return keysOf(plain.copyOf(32), plain.copyOfRange(32, 64))
    }

    /** True when the key file exists, readable or not. */
    override fun hasKeys(): Boolean = file.exists()

    override fun delete() {
        if (file.exists() && !file.delete()) throw KeyStorageException("cannot delete ${file.name}")
        staleTempFiles(file).forEach { it.delete() }
    }

    private fun keysOf(seed: ByteArray, dhPrivate: ByteArray) =
        DeviceKeys(Ed25519.fromSeed(seed), X25519.fromPrivate(dhPrivate))

    private companion object {
        val MAGIC = "KSK1".encodeToByteArray()
        val AAD = "kinsight.software-keys.v1".encodeToByteArray()
    }
}
