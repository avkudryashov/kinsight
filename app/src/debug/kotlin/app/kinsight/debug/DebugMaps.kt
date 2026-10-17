package app.kinsight.debug

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.CatalogBlock
import app.kinsight.core.maps.Install
import app.kinsight.core.maps.TileGrid
import java.io.File
import java.security.MessageDigest

/**
 * Debug builds only: the device check of the offline map (spec §3) without published blocks. MAP_INSTALL_LOCAL puts a
 * locally built block on the phone through the store's own install path; MAP_STATUS says what is there. The answers go
 * to adb only (the receiver logs their length), since cells say where a child has been.
 */
object DebugMaps {
    /**
     * Installs the file at [path] as the block of cell ([x], [y]): its size and sha256 are taken first, then
     * [BlockStore.install] copies it to a partial file, checks both against them and renames it (spec §3), so a file
     * that changes while it is read is refused. The cell is marked used at [nowMs] (a newer mark is kept), which keeps
     * it 7 days. A catalog that lists the cell with another sha256 replaces it at the next run.
     */
    fun installLocal(store: BlockStore, x: Int, y: Int, path: String?, nowMs: Long): String {
        path ?: error("path extra missing")
        require(x in 0 until TileGrid.SIZE && y in 0 until TileGrid.SIZE) { "x and y must be cells of z${TileGrid.ZOOM}" }
        val file = File(path)
        check(file.isFile && file.canRead()) { "cannot read $path" }
        val bytes = file.length()
        check(bytes > 0) { "$path is empty" }
        val sha = sha256(file)
        val cell = Cell(x, y)
        val result = file.inputStream().use { store.install(CatalogBlock(cell, bytes, sha, file.toURI().toString()), it) }
        check(result == Install.OK) { "$path changed while it was read" }
        store.updateLedger { l ->
            l.copy(installed = l.installed + (cell to sha), usage = l.usage + (cell to maxOf(nowMs, l.usage[cell] ?: Long.MIN_VALUE)))
        }
        return "installed $x $y $bytes"
    }

    /** The block files, the ledger's last-used time of each cell (ms), the catalog version and the free bytes. */
    fun status(store: BlockStore): String {
        val present = store.present().sortedWith(ORDER).joinToString(",") { key(it) }.ifEmpty { "none" }
        val used = store.ledger().usage.entries.sortedWith(compareBy(ORDER) { it.key })
            .joinToString(",") { "${key(it.key)}@${it.value}" }.ifEmpty { "none" }
        val catalog = store.catalog()?.version ?: "none"
        return "present=$present used=$used catalog=$catalog free=${store.freeBytes()}"
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
