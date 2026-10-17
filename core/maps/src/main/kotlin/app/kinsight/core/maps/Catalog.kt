package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Offline map spec §2: GitHub Releases of the project are the map's only source. */
object MapLinks {
    const val DOWNLOAD_PREFIX = "https://github.com/avkudryashov/kinsight/releases/download/"

    /** A release of its own that only ever holds catalog.json: the app releases are «Latest», so /latest/ is theirs. */
    const val CATALOG_URL = DOWNLOAD_PREFIX + "maps-catalog/catalog.json"
}

/** One block of the catalog; [sha256] lowercase hex. */
data class CatalogBlock(val cell: Cell, val bytes: Long, val sha256: String, val url: String) {
    override fun toString() = "CatalogBlock"
}

/** [version] `YYYY-MM`: string order is date order. */
data class Catalog(val version: String, val blocks: Map<Cell, CatalogBlock>)

object CatalogParser {
    /** A block bigger than this is not one of ours (a whole city is about 10 MB). */
    const val MAX_BLOCK_BYTES = 200L shl 20

    private val VERSION = Regex("""\d{4}-\d{2}""")
    private val SHA = Regex("[0-9a-f]{64}")

    /** Null for anything that is not a catalog; a block that breaks a rule is left out and the rest is kept. */
    fun parse(text: String): Catalog? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val version = (root["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(VERSION::matches) ?: return null
        val list = root["blocks"] as? JsonArray ?: return null
        return Catalog(version, list.mapNotNull { (it as? JsonObject)?.let(::block) }.associateBy { it.cell })
    }

    private fun block(o: JsonObject): CatalogBlock? {
        val x = (o["x"] as? JsonPrimitive)?.intOrNull ?: return null
        val y = (o["y"] as? JsonPrimitive)?.intOrNull ?: return null
        if (x !in 0 until TileGrid.SIZE || y !in 0 until TileGrid.SIZE) return null
        val cell = Cell(x, y)
        val bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..MAX_BLOCK_BYTES } ?: return null
        val sha = (o["sha256"] as? JsonPrimitive)?.contentOrNull?.takeIf(SHA::matches) ?: return null
        val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return null
        // Only a maps-* release of the project, and only the file of this very cell.
        val ok = url.startsWith(MapLinks.DOWNLOAD_PREFIX + "maps-") && url.endsWith("/${cell.name}.pmtiles") &&
            ".." !in url && '?' !in url && '#' !in url
        return if (ok) CatalogBlock(cell, bytes, sha, url) else null
    }
}
