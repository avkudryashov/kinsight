package app.kinsight.core.keystore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * This device's Yandex tokens (spec §6 «Хранение») for the storage [storageGeneration] / [storageId] of group [groupHex].
 * [owner]: this device owns the storage and keeps [refreshToken] (spec §6: only the owner device has it); every other
 * device keeps only the shared access token. Never logged.
 */
class CloudTokens(
    val groupHex: String,
    val owner: Boolean,
    val storageGeneration: Long,
    storageId: ByteArray,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
) {
    val storageId: ByteArray = storageId.copyOf()

    init {
        require(storageId.size == 16) { "storage id must be 16 bytes" }
        require(owner || refreshToken == null) { "only the storage owner keeps a refresh token" }
    }

    override fun toString() = "CloudTokens(owner=$owner, generation=$storageGeneration, expiresAtMs=$expiresAtMs, refresh=${refreshToken != null})"
}

/** `noBackupFilesDir/kinsight-cloud-tokens.v1`: "KCT1" + sealed tokens. Blocking file and Keystore I/O. */
class CloudTokenVault(file: File, wrap: KeyWrap) {
    private val sealed = SealedFile(file, wrap, MAGIC)

    @Synchronized
    fun read(): CloudTokens? = sealed.read()?.let(::decode)

    @Synchronized
    fun write(t: CloudTokens) = sealed.write(encode(t))

    @Synchronized
    fun delete() = sealed.delete()

    private fun encode(t: CloudTokens): ByteArray = try {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeUTF(t.groupHex)
            o.writeBoolean(t.owner)
            o.writeLong(t.storageGeneration)
            o.write(t.storageId)
            o.writeUTF(t.accessToken)
            o.writeBoolean(t.refreshToken != null)
            t.refreshToken?.let { o.writeUTF(it) }
            o.writeLong(t.expiresAtMs)
        }
        out.toByteArray()
    } catch (e: IOException) {
        throw KeyStorageException("cloud tokens cannot be encoded", e)
    }

    private fun decode(b: ByteArray): CloudTokens = try {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            val group = i.readUTF()
            val owner = i.readBoolean()
            val generation = i.readLong()
            val storageId = ByteArray(16).also { i.readFully(it) }
            val access = i.readUTF()
            val refresh = if (i.readBoolean()) i.readUTF() else null
            val expiresAt = i.readLong()
            if (i.available() != 0) throw IOException("trailing bytes")
            CloudTokens(group, owner, generation, storageId, access, refresh, expiresAt)
        }
    } catch (e: IOException) {
        throw KeyStorageException("cloud tokens are malformed", e)
    } catch (e: IllegalArgumentException) {
        throw KeyStorageException("cloud tokens are malformed", e)
    }

    companion object {
        const val FILE_NAME = "kinsight-cloud-tokens.v1"
        private val MAGIC = "KCT1".encodeToByteArray()
    }
}
