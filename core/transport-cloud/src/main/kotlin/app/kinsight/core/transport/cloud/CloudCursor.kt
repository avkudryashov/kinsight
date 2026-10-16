package app.kinsight.core.transport.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** A key generation and the [GroupKey.fingerprint] of the key: two admins rotating at once give one generation two keys. */
data class KeyId(val epoch: Int, val fingerprint: String) {
    /**
     * An own pack kept as it is (A-4): found in our folder, signed by us, sealed with a key we lack. Its range counts as
     * uploaded; it is never overwritten, re-sealed or deleted, since its entries are not in our log.
     */
    val kept: Boolean get() = fingerprint == KEPT

    companion object {
        /** [fingerprint] of a [kept] pack; a real one is hex. */
        const val KEPT = "kept"

        fun kept(epoch: Int) = KeyId(epoch, KEPT)
    }
}

/**
 * A pack that could not be opened yet, sealed with generation [epoch] (-1 when unknown). [failedKey]: the fingerprint of our
 * key of [epoch] under which the ciphertext did not open; the pack is retried only once the ring holds another key for it.
 * [membershipHash]: set for an unknown author, the membership it was unknown in; retried only once membership changed (a
 * token holder can plant a folder of its own, and it must not be downloaded on every run).
 */
data class DeferredPack(val epoch: Int, val failedKey: String? = null, val membershipHash: String? = null)

/**
 * Per-device cloud progress. [uploadedUpTo]: own seqs up to here are in packs on the Disk. [seen]: packs already handled,
 * "<device>/<name>/<sha256>" to the key generation they were sealed with (-1 when they did not open, -2 when skipped as
 * covered by their name without reading the generation), pruned to the last listing. [lastCompactionMs]: wall clock of the
 * last compaction. [ownPacks]: names of the own packs on the Disk to the key each is sealed with. [deferred]: packs that
 * could not be opened yet (unknown author, key generation or key), same keys as [seen]; retried once the ring holds a key
 * that may open them, and they keep their generation in the ring. [locationDue]: own packs that carry LOCATION entries
 * to the wall clock time when every one of them is past the 48 h retention (spec §8); the pack is re-sealed once then,
 * without them, and leaves the map ("location-clean"). A pack not in the map carries none.
 */
data class CloudCursor(
    val uploadedUpTo: Long = 0,
    val seen: Map<String, Int> = emptyMap(),
    val lastCompactionMs: Long = 0,
    val ownPacks: Map<String, KeyId> = emptyMap(),
    val deferred: Map<String, DeferredPack> = emptyMap(),
    val locationDue: Map<String, Long> = emptyMap(),
)

interface CloudCursorStore {
    suspend fun load(): CloudCursor

    /** Must be durable when it returns: a crash after an upload must not upload the range again with a gap below. */
    suspend fun save(cursor: CloudCursor)
}

class InMemoryCursorStore(var cursor: CloudCursor = CloudCursor()) : CloudCursorStore {
    override suspend fun load() = cursor

    override suspend fun save(cursor: CloudCursor) {
        this.cursor = cursor
    }
}

/**
 * The cursor at rest (Y2: one Room row). Version byte, then the fields in declaration order, maps as a count and their
 * entries. [decode] throws IOException for anything it did not write, including a cut or longer buffer. Version 1 (before
 * Y4) has no [CloudCursor.locationDue]: no own pack carried a LOCATION entry then, so it decodes to an empty map.
 */
object CloudCursorCodec {
    private const val VERSION = 2
    private const val VERSION_1 = 1
    private const val MAX_ITEMS = 1_000_000

    fun encode(c: CloudCursor): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeByte(VERSION)
            o.writeLong(c.uploadedUpTo)
            o.writeLong(c.lastCompactionMs)
            o.writeInt(c.seen.size)
            for ((k, v) in c.seen) {
                o.writeUTF(k)
                o.writeInt(v)
            }
            o.writeInt(c.ownPacks.size)
            for ((k, v) in c.ownPacks) {
                o.writeUTF(k)
                o.writeInt(v.epoch)
                o.writeUTF(v.fingerprint)
            }
            o.writeInt(c.deferred.size)
            for ((k, v) in c.deferred) {
                o.writeUTF(k)
                o.writeInt(v.epoch)
                o.writeOptional(v.failedKey)
                o.writeOptional(v.membershipHash)
            }
            o.writeInt(c.locationDue.size)
            for ((k, v) in c.locationDue) {
                o.writeUTF(k)
                o.writeLong(v)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): CloudCursor {
        val input = ByteArrayInputStream(bytes)
        val i = DataInputStream(input)
        val version = i.readUnsignedByte()
        if (version != VERSION && version != VERSION_1) throw IOException("unknown cloud cursor version")
        val uploaded = i.readLong()
        val compaction = i.readLong()
        val seen = (0 until i.count()).associate { i.readUTF() to i.readInt() }
        val own = (0 until i.count()).associate { i.readUTF() to KeyId(i.readInt(), i.readUTF()) }
        val deferred = (0 until i.count()).associate { i.readUTF() to DeferredPack(i.readInt(), i.readOptional(), i.readOptional()) }
        val due = if (version == VERSION_1) emptyMap() else (0 until i.count()).associate { i.readUTF() to i.readLong() }
        if (input.available() != 0) throw IOException("trailing bytes in cloud cursor")
        return CloudCursor(uploaded, seen, compaction, own, deferred, due)
    }

    private fun DataInputStream.count(): Int = readInt().also { if (it !in 0..MAX_ITEMS) throw IOException("bad count $it") }

    private fun DataOutputStream.writeOptional(s: String?) {
        writeBoolean(s != null)
        if (s != null) writeUTF(s)
    }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null
}
