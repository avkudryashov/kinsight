package app.kinsight.core.keystore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** A token of storage generation [generation] of group [groupHex] no longer in use but still valid until revoked (spec §6, §7). Never logged. */
class RetiredToken(val groupHex: String, val generation: Long, val accessToken: String) {
    override fun toString() = "RetiredToken(generation=$generation)"
}

/**
 * `noBackupFilesDir/kinsight-cloud-retired.v1`: "KRT1" + sealed list of [RetiredToken], at most [MAX] (the newest kept).
 * Writing an empty list deletes the file. Blocking file and Keystore I/O.
 */
class RetiredTokenFile(file: File, wrap: KeyWrap) {
    private val sealed = SealedFile(file, wrap, MAGIC)

    @Synchronized
    fun read(): List<RetiredToken> = sealed.read()?.let(::decode) ?: emptyList()

    @Synchronized
    fun write(tokens: List<RetiredToken>) = if (tokens.isEmpty()) sealed.delete() else sealed.write(encode(tokens.takeLast(MAX)))

    @Synchronized
    fun delete() = sealed.delete()

    private fun encode(tokens: List<RetiredToken>): ByteArray = try {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeInt(tokens.size)
            for (t in tokens) {
                o.writeUTF(t.groupHex)
                o.writeLong(t.generation)
                o.writeUTF(t.accessToken)
            }
        }
        out.toByteArray()
    } catch (e: IOException) {
        throw KeyStorageException("retired tokens cannot be encoded", e)
    }

    private fun decode(b: ByteArray): List<RetiredToken> = try {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            val n = i.readInt()
            if (n !in 0..MAX) throw IOException("bad count $n")
            val out = (0 until n).map { RetiredToken(i.readUTF(), i.readLong(), i.readUTF()) }
            if (i.available() != 0) throw IOException("trailing bytes")
            out
        }
    } catch (e: IOException) {
        throw KeyStorageException("retired tokens are malformed", e)
    }

    companion object {
        const val FILE_NAME = "kinsight-cloud-retired.v1"
        const val MAX = 8
        private val MAGIC = "KRT1".encodeToByteArray()
    }
}
