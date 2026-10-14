package app.kinsight.core.keystore

import java.io.File

/**
 * A small secret file: [magic] followed by wrap.seal(payload, aad = magic), replaced atomically. A missing file reads as
 * null; anything unreadable or not sealed by this wrap is a KeyStorageException, never "nothing there".
 */
internal class SealedFile(private val file: File, private val wrap: KeyWrap, private val magic: ByteArray) {
    fun read(): ByteArray? {
        if (!file.exists()) return null
        val blob = try {
            file.readBytes()
        } catch (e: Exception) {
            throw KeyStorageException("cannot read ${file.name}", e)
        }
        if (blob.size <= magic.size || !blob.copyOf(magic.size).contentEquals(magic)) throw KeyStorageException("${file.name} has a bad header")
        return try {
            wrap.open(blob.copyOfRange(magic.size, blob.size), magic)
        } catch (e: Exception) {
            throw KeyStorageException("cannot unseal ${file.name}", e)
        }
    }

    fun write(payload: ByteArray) {
        val sealed = try {
            wrap.seal(payload, magic)
        } catch (e: Exception) {
            throw KeyStorageException("cannot seal ${file.name}", e)
        }
        writeAtomically(file, magic + sealed)
    }

    fun delete() {
        if (file.exists() && !file.delete()) throw KeyStorageException("cannot delete ${file.name}")
        staleTempFiles(file).forEach { it.delete() }
    }
}
