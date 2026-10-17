package app.kinsight.core.maps

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/** PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md), what a block file needs of it. */
object PmTiles {
    const val HEADER_BYTES = 127

    /** The header and the root directory must fit the first 16 KiB, so a reader gets both with one read. */
    const val FIRST_READ_BYTES = 16_384
    const val COMPRESSION_GZIP = 2
    const val TILE_TYPE_MVT = 1
    internal val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

    /** The Hilbert tile id: every tile of the lower zooms first, then the position on this zoom's Hilbert curve. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "no such tile" }
        var acc = 0L
        for (t in 0 until z) acc += 1L shl (2 * t)
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val swap = tx
                tx = ty
                ty = swap
            }
            s /= 2
        }
        return acc + d
    }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    /** The deepest zoom of a PMTiles v3 [file]; null for anything else (debug installs check it, spec §3). */
    fun maxZoomOf(file: File): Int? {
        if (!file.isFile || file.length() < HEADER_BYTES) return null
        val head = ByteArray(HEADER_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        if (!head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || head[7].toInt() != 3) return null
        return head[101].toInt() and 0xff
    }
}

/**
 * One block file (offline map spec §7): tiles come in any order and wait in [scratch]; [finish] writes the header,
 * one gzip root directory, the gzip metadata and the tiles in tile id order (clustered). Tiles are gzip members as
 * OpenFreeMap sends them; every tile has its own entry. [close] deletes the scratch.
 */
class PmTilesWriter(private val scratch: File) : Closeable {
    private class Entry(val id: Long, val offset: Long, val length: Int)

    private val data = RandomAccessFile(scratch, "rw").apply { setLength(0) }
    private val entries = ArrayList<Entry>()
    private val ids = HashSet<Long>()
    private var minZoom = Int.MAX_VALUE
    private var maxZoom = -1

    fun add(z: Int, x: Int, y: Int, gzipped: ByteArray) {
        require(gzipped.size >= 2 && gzipped[0] == 0x1f.toByte() && gzipped[1] == 0x8b.toByte()) { "a tile must be gzip" }
        val id = PmTiles.tileId(z, x, y)
        require(ids.add(id)) { "a tile came twice" }
        val offset = data.length()
        data.seek(offset)
        data.write(gzipped)
        entries += Entry(id, offset, gzipped.size)
        minZoom = minOf(minZoom, z)
        maxZoom = maxOf(maxZoom, z)
    }

    fun finish(target: File, bounds: Bounds, metadata: String) {
        check(entries.isNotEmpty()) { "no tiles" }
        val sorted = entries.sortedBy { it.id }
        val directory = PmTiles.gzip(directory(sorted))
        check(PmTiles.HEADER_BYTES + directory.size <= PmTiles.FIRST_READ_BYTES) { "the root directory is over 16 KiB" }
        val meta = PmTiles.gzip(metadata.encodeToByteArray())
        val dirOffset = PmTiles.HEADER_BYTES.toLong()
        val metaOffset = dirOffset + directory.size
        val dataOffset = metaOffset + meta.size
        val dataLength = sorted.sumOf { it.length.toLong() }
        val n = sorted.size.toLong()
        val h = ByteBuffer.allocate(PmTiles.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put(PmTiles.MAGIC).put(3.toByte())
        h.putLong(dirOffset).putLong(directory.size.toLong())
        h.putLong(metaOffset).putLong(meta.size.toLong())
        h.putLong(dataOffset).putLong(0L) // no leaf directories
        h.putLong(dataOffset).putLong(dataLength)
        h.putLong(n).putLong(n).putLong(n)
        h.put(1.toByte()) // clustered
        h.put(PmTiles.COMPRESSION_GZIP.toByte()).put(PmTiles.COMPRESSION_GZIP.toByte()).put(PmTiles.TILE_TYPE_MVT.toByte())
        h.put(minZoom.toByte()).put(maxZoom.toByte())
        h.putInt(e7(bounds.west)).putInt(e7(bounds.south)).putInt(e7(bounds.east)).putInt(e7(bounds.north))
        h.put(minZoom.toByte()).putInt(e7((bounds.west + bounds.east) / 2)).putInt(e7((bounds.south + bounds.north) / 2))
        FileOutputStream(target).use { out ->
            out.write(h.array())
            out.write(directory)
            out.write(meta)
            val buf = ByteArray(64 * 1024)
            for (e in sorted) {
                data.seek(e.offset)
                var left = e.length
                while (left > 0) {
                    val got = data.read(buf, 0, minOf(left, buf.size))
                    if (got < 0) throw IOException("scratch ended early")
                    out.write(buf, 0, got)
                    left -= got
                }
            }
            out.fd.sync()
        }
    }

    override fun close() {
        data.close()
        scratch.delete()
    }

    /** Spec «Directories»: count, id deltas, run lengths, lengths, offsets (0: right after the previous tile). */
    private fun directory(sorted: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, sorted.size.toLong())
        var last = 0L
        for (e in sorted) {
            varint(out, e.id - last)
            last = e.id
        }
        for (e in sorted) varint(out, 1)
        for (e in sorted) varint(out, e.length.toLong())
        // The data is written in this order with no gaps: the first tile at 0 (written as 0 + 1), each next one follows.
        for (i in sorted.indices) varint(out, if (i == 0) 1 else 0)
        return out.toByteArray()
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun e7(deg: Double) = (deg * 1e7).roundToInt()
}
