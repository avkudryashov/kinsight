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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What the store keeps next to the files (offline map spec §3): [usage] the newest point time per cell, [installed]
 * the sha256 each block file was verified against, [catalogFetchedMs] when the catalog was last asked for.
 */
data class BlockLedger(
    val usage: Map<Cell, Long> = emptyMap(),
    val installed: Map<Cell, String> = emptyMap(),
    val catalogFetchedMs: Long? = null,
)

enum class Install { OK, MISMATCH }

/**
 * The block files under [dir] (`filesDir/maps`): `z10-<x>-<y>.pmtiles`, the cached `catalog.json` and `ledger.json`.
 * Only a verified file ever carries the `.pmtiles` name (spec §3), so the preview never opens half a block.
 */
class BlockStore(val dir: File, private val free: (File) -> Long = { it.usableSpace }) {
    fun file(cell: Cell): File = File(dir, cell.name + EXT)

    fun present(): Set<Cell> = dir.listFiles().orEmpty().mapNotNull { f ->
        BLOCK.matchEntire(f.name)?.let { m -> Cell(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
    }.toSet()

    fun freeBytes(): Long {
        dir.mkdirs()
        return free(dir)
    }

    @Synchronized
    fun ledger(): BlockLedger = File(dir, LEDGER).takeIf { it.isFile }?.let { decode(it.readText()) } ?: BlockLedger()

    /** One read-change-write at a time: the check and the download run may both write. */
    @Synchronized
    fun updateLedger(change: (BlockLedger) -> BlockLedger): BlockLedger = change(ledger()).also { writeAtomically(LEDGER, encode(it)) }

    fun catalog(): Catalog? = File(dir, CATALOG).takeIf { it.isFile }?.readText()?.let(CatalogParser::parse)

    fun saveCatalog(text: String) = writeAtomically(CATALOG, text)

    /**
     * Writes [input] to a partial file, counting and hashing; renames it over the block only when the size and the
     * sha256 match the catalog. The partial is deleted in every other case, an exception from [input] included.
     */
    fun install(block: CatalogBlock, input: InputStream): Install {
        dir.mkdirs()
        val part = File(dir, block.cell.name + EXT + PART)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > block.bytes) return Install.MISMATCH
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
            if (total != block.bytes || hex(digest.digest()) != block.sha256) return Install.MISMATCH
            // A rename over the old block: a preview that has the old file open keeps reading it until it lets go.
            Files.move(part.toPath(), file(block.cell).toPath(), StandardCopyOption.ATOMIC_MOVE)
            return Install.OK
        } finally {
            part.delete()
        }
    }

    fun delete(cell: Cell) {
        file(cell).delete()
    }

    fun sweepPartials() {
        dir.listFiles().orEmpty().filter { it.name.endsWith(PART) || it.name.endsWith(TMP) }.forEach { it.delete() }
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
        l.catalogFetchedMs?.let { put("catalogFetchedMs", it) }
    }.toString()

    private fun decode(text: String): BlockLedger {
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return BlockLedger()
        return BlockLedger(
            usage = cells(o["usage"]) { (it as? JsonPrimitive)?.longOrNull },
            installed = cells(o["installed"]) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            catalogFetchedMs = (o["catalogFetchedMs"] as? JsonPrimitive)?.longOrNull,
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
        const val CATALOG = "catalog.json"
        private const val TMP = ".tmp"
        private val BLOCK = Regex("""z10-(\d{1,4})-(\d{1,4})\.pmtiles""")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
