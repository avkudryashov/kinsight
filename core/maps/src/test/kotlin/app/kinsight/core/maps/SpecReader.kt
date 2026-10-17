package app.kinsight.core.maps

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * A PMTiles v3 reader written from https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md alone: the other
 * side of the writer's round trip. It decodes tile ids back to z/x/y with its own Hilbert code (the inverse of the
 * writer's), so a shared mistake cannot pass.
 */
internal class SpecReader(file: File) {
    class Header(
        val rootOffset: Long, val rootLength: Long, val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long, val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Int, val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int, val minLonE7: Int, val minLatE7: Int, val maxLonE7: Int, val maxLatE7: Int,
    )

    class Entry(val id: Long, val runLength: Long, val offset: Long, val length: Long)

    val header: Header
    val entries: List<Entry>
    val tiles: Map<Triple<Int, Int, Int>, ByteArray>
    val metadata: String

    init {
        val all = file.readBytes()
        check(String(all, 0, 7, Charsets.US_ASCII) == "PMTiles") { "magic" }
        check(all[7].toInt() == 3) { "version" }
        val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8)
        header = Header(
            b.long, b.long, b.long, b.long, b.long, b.long, b.long, b.long, b.long, b.long, b.long,
            b.get().toInt(), b.get().toInt(), b.get().toInt(), b.get().toInt(),
            b.get().toInt() and 0xff, b.get().toInt() and 0xff, b.int, b.int, b.int, b.int,
        )
        val dir = gunzip(all.copyOfRange(header.rootOffset.toInt(), (header.rootOffset + header.rootLength).toInt()))
        metadata = gunzip(all.copyOfRange(header.metadataOffset.toInt(), (header.metadataOffset + header.metadataLength).toInt())).decodeToString()
        var i = 0
        fun varint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                val x = dir[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (k in 0 until n) { last += varint(); ids[k] = last }
        val runs = LongArray(n) { varint() }
        val lengths = LongArray(n) { varint() }
        val offsets = LongArray(n)
        for (k in 0 until n) {
            val v = varint()
            offsets[k] = if (v == 0L && k > 0) offsets[k - 1] + lengths[k - 1] else v - 1
        }
        entries = (0 until n).map { Entry(ids[it], runs[it], offsets[it], lengths[it]) }
        val out = HashMap<Triple<Int, Int, Int>, ByteArray>()
        for (e in entries) {
            val start = (header.dataOffset + e.offset).toInt()
            val bytes = all.copyOfRange(start, start + e.length.toInt())
            for (r in 0 until e.runLength) out[zxy(e.id + r)] = bytes
        }
        tiles = out
    }

    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readBytes() }

    /** The spec's id to z/x/y: find the zoom by counting tiles, then walk the Hilbert curve. */
    private fun zxy(id: Long): Triple<Int, Int, Int> {
        var acc = 0L
        var z = 0
        while (acc + (1L shl (2 * z)) <= id) { acc += 1L shl (2 * z); z++ }
        var t = id - acc
        var tx = 0L
        var ty = 0L
        var s = 1L
        val n = 1L shl z
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val swap = tx; tx = ty; ty = swap
            }
            tx += s * rx
            ty += s * ry
            t /= 4
            s *= 2
        }
        return Triple(z, tx.toInt(), ty.toInt())
    }
}
