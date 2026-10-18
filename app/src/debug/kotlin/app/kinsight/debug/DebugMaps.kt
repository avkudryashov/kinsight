package app.kinsight.debug

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.BlockVersion
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.PmTiles
import app.kinsight.core.maps.TileGrid
import app.kinsight.core.maps.TileSource
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Debug builds only: the device check of the offline map (spec §3, §7) with a block from elsewhere, for example copied
 * from a phone that downloaded it. MAP_INSTALL_LOCAL puts it on the phone through the store's own install path;
 * MAP_STATUS says what is there. The answers go to adb only (the receiver logs their length), since cells say where a
 * child has been.
 */
object DebugMaps {
    /**
     * Installs the PMTiles v3 file at [path] as the block of cell ([x], [y]): its size, sha256 and max zoom are taken
     * first, then [BlockStore.install] copies it to a partial file, checks the copy against them and renames it, so a
     * file that changes while it is read is refused. It is recorded as `local/<max zoom>` (never renewed by age) and
     * the cell is marked used at [nowMs] (a newer mark is kept), which keeps it 7 days. A cell that needs more depth
     * (z14 where a child now is) is fetched from OpenFreeMap at the next run.
     */
    fun installLocal(store: BlockStore, x: Int, y: Int, path: String?, nowMs: Long): String {
        path ?: error("path extra missing")
        require(x in 0 until TileGrid.SIZE && y in 0 until TileGrid.SIZE) { "x and y must be cells of z${TileGrid.ZOOM}" }
        val file = File(path)
        check(file.isFile && file.canRead()) { "cannot read $path" }
        val bytes = file.length()
        check(bytes > 0) { "$path is empty" }
        val maxZoom = PmTiles.maxZoomOf(file)
        check(maxZoom != null && maxZoom in TileGrid.ZOOM..TileGrid.MAX_TILE_ZOOM) {
            "$path is not a PMTiles v3 block of z${TileGrid.ZOOM}..z${TileGrid.MAX_TILE_ZOOM}"
        }
        val sha = sha256(file)
        val cell = Cell(x, y)
        store.install(cell) { part, _ ->
            file.inputStream().use { input ->
                FileOutputStream(part).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            check(part.length() == bytes && sha256(part) == sha) { "$path changed while it was read" }
        }
        store.updateLedger { l ->
            l.copy(
                installed = l.installed + (cell to BlockVersion(BlockVersion.LOCAL, maxZoom).encode()),
                usage = l.usage + (cell to maxOf(nowMs, l.usage[cell] ?: Long.MIN_VALUE)),
            )
        }
        return "installed $x $y $bytes"
    }

    /** The block files, the ledger's last-used time of each cell (ms), the build of the cached TileJSON and the free bytes. */
    fun status(store: BlockStore): String {
        val ledger = store.ledger()
        val present = store.present().sortedWith(ORDER).joinToString(",") { key(it) }.ifEmpty { "none" }
        val used = ledger.usage.entries.sortedWith(compareBy(ORDER) { it.key })
            .joinToString(",") { "${key(it.key)}@${it.value}" }.ifEmpty { "none" }
        val source = ledger.source?.let(TileSource::of)?.build ?: "none"
        return "present=$present used=$used source=$source free=${store.freeBytes()}"
    }

    private val ORDER = compareBy<Cell>({ it.x }, { it.y })

    private fun key(c: Cell) = "${c.x}:${c.y}"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
