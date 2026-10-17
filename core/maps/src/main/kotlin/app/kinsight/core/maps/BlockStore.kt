package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the store keeps next to the files (offline map spec §3, §7): [usage] the newest point time per cell of a
 * point's neighbourhood, [centres] the newest point time per point's own cell (those come to z14), [installed]
 * [BlockVersion.encode] of each file, [capped] the cells whose download passed the size cap and when, [source] the
 * cached TileJSON's tile address ([TileSource.template]) and [sourceFetchedMs] when it was asked for.
 */
data class BlockLedger(
    val usage: Map<Cell, Long> = emptyMap(),
    val installed: Map<Cell, String> = emptyMap(),
    val centres: Map<Cell, Long> = emptyMap(),
    val capped: Map<Cell, Long> = emptyMap(),
    val source: String? = null,
    val sourceFetchedMs: Long? = null,
)

/**
 * The block files under [dir] (`filesDir/maps`): `z10-<x>-<y>.pmtiles` and `ledger.json`. Only a finished file ever
 * carries the `.pmtiles` name (spec §3), so the preview never opens half a block.
 */
class BlockStore(val dir: File, private val free: (File) -> Long = { it.usableSpace }) {
    fun file(cell: Cell): File = File(dir, cell.name + EXT)

    fun present(): Set<Cell> = dir.listFiles().orEmpty().mapNotNull { f ->
        BLOCK.matchEntire(f.name)?.let { m -> Cell(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
    }.toSet()

    fun freeBytes(): Long {
        // Never create the folder here: after a leave it must stay gone (MapBlocks relies on it).
        return free(dir.takeIf { it.exists() } ?: dir.parentFile ?: dir)
    }

    @Synchronized
    fun ledger(): BlockLedger = File(dir, LEDGER).takeIf { it.isFile }?.let { decode(it.readText()) } ?: BlockLedger()

    /** One read-change-write at a time: the check and the download run may both write. */
    @Synchronized
    fun updateLedger(change: (BlockLedger) -> BlockLedger): BlockLedger = change(ledger()).also { writeAtomically(LEDGER, encode(it)) }

    /**
     * Spec §7: [write] fills the partial file `part` (and may keep tiles in `scratch` next to it); only when it returns
     * is `part` renamed over the block. Both are deleted in every case; an exception from [write] is rethrown.
     */
    fun install(cell: Cell, write: (part: File, scratch: File) -> Unit) {
        dir.mkdirs()
        val part = File(dir, cell.name + EXT + PART)
        val scratch = File(dir, cell.name + SCRATCH + PART)
        try {
            write(part, scratch)
            // A rename over the old block: a preview that has the old file open keeps reading it until it lets go.
            Files.move(part.toPath(), file(cell).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.delete()
            scratch.delete()
        }
    }

    fun delete(cell: Cell) {
        file(cell).delete()
    }

    /** Partial files, temporary ledgers and the catalog of the GitHub days. */
    fun sweepPartials() {
        dir.listFiles().orEmpty().filter { it.name.endsWith(PART) || it.name.endsWith(TMP) || it.name == LEGACY_CATALOG }.forEach { it.delete() }
    }

    fun forget() {
        dir.deleteRecursively()
    }

    private fun writeAtomically(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, name + TMP)
        FileOutputStream(tmp).use {
            it.write(text.encodeToByteArray())
            it.fd.sync()
        }
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(l: BlockLedger): String = buildJsonObject {
        putJsonObject("usage") { l.usage.forEach { (c, t) -> put(key(c), t) } }
        putJsonObject("installed") { l.installed.forEach { (c, s) -> put(key(c), s) } }
        putJsonObject("centres") { l.centres.forEach { (c, t) -> put(key(c), t) } }
        putJsonObject("capped") { l.capped.forEach { (c, t) -> put(key(c), t) } }
        l.source?.let { put("source", it) }
        l.sourceFetchedMs?.let { put("sourceFetchedMs", it) }
    }.toString()

    private fun decode(text: String): BlockLedger {
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return BlockLedger()
        val long = { e: JsonElement -> (e as? JsonPrimitive)?.longOrNull }
        return BlockLedger(
            usage = cells(o["usage"], long),
            installed = cells(o["installed"]) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            centres = cells(o["centres"], long),
            capped = cells(o["capped"], long),
            source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            sourceFetchedMs = (o["sourceFetchedMs"] as? JsonPrimitive)?.longOrNull,
        )
    }

    private fun <T : Any> cells(e: JsonElement?, value: (JsonElement) -> T?): Map<Cell, T> =
        ((e as? JsonObject) ?: emptyMap<String, JsonElement>()).mapNotNull { (k, v) ->
            cellOf(k)?.let { c -> value(v)?.let { c to it } }
        }.toMap()

    private fun key(c: Cell) = "${c.x}:${c.y}"

    private fun cellOf(key: String): Cell? {
        val p = key.split(':')
        if (p.size != 2) return null
        val x = p[0].toIntOrNull() ?: return null
        val y = p[1].toIntOrNull() ?: return null
        return Cell(x, y)
    }

    companion object {
        const val EXT = ".pmtiles"
        const val PART = ".part"
        const val LEDGER = "ledger.json"
        private const val SCRATCH = ".tiles"
        private const val TMP = ".tmp"
        private const val LEGACY_CATALOG = "catalog.json"
        private val BLOCK = Regex("""z10-(\d{1,4})-(\d{1,4})\.pmtiles""")
    }
}
