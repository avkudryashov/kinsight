package app.kinsight.core.keystore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * The group key generations this device holds (spec §5, cloud Y2), for one group: `noBackupFilesDir/kinsight-group-keys.v1`,
 * "KGK1" + sealed (group hex, then epoch and 32-byte key per generation). Blocking file and Keystore I/O.
 */
class GroupKeyFile(file: File, wrap: KeyWrap) {
    class Contents(val groupHex: String, keys: Map<Int, ByteArray>) {
        val keys: Map<Int, ByteArray> = keys.mapValues { it.value.copyOf() }

        init {
            require(keys.values.all { it.size == 32 }) { "group keys must be 32 bytes" }
        }

        override fun toString() = "GroupKeyFile.Contents(group=${groupHex.take(8)}, epochs=${keys.keys.sorted()})"
    }

    private val sealed = SealedFile(file, wrap, MAGIC)

    @Synchronized
    fun read(): Contents? = sealed.read()?.let(::decode)

    @Synchronized
    fun write(c: Contents) = sealed.write(encode(c))

    @Synchronized
    fun delete() = sealed.delete()

    private fun encode(c: Contents): ByteArray = try {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeUTF(c.groupHex)
            o.writeInt(c.keys.size)
            for ((epoch, key) in c.keys.toSortedMap()) {
                o.writeInt(epoch)
                o.write(key)
            }
        }
        out.toByteArray()
    } catch (e: IOException) {
        throw KeyStorageException("group keys cannot be encoded", e)
    }

    private fun decode(b: ByteArray): Contents = try {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            val group = i.readUTF()
            val n = i.readInt()
            if (n !in 0..MAX_GENERATIONS) throw IOException("bad generation count $n")
            val keys = LinkedHashMap<Int, ByteArray>()
            repeat(n) {
                val epoch = i.readInt()
                if (epoch < 0 || epoch in keys) throw IOException("bad epoch")
                keys[epoch] = ByteArray(32).also { k -> i.readFully(k) }
            }
            if (i.available() != 0) throw IOException("trailing bytes")
            Contents(group, keys)
        }
    } catch (e: IOException) {
        throw KeyStorageException("group keys are malformed", e)
    }

    companion object {
        const val FILE_NAME = "kinsight-group-keys.v1"
        private const val MAX_GENERATIONS = 1024
        private val MAGIC = "KGK1".encodeToByteArray()
    }
}
