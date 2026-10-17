package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/** A tile as the server sent it: [gzip] when its Content-Encoding said so (offline map spec §7). */
class Tile(val bytes: ByteArray, val gzip: Boolean)

/** HTTP 404 from OpenFreeMap: the build of the cached TileJSON is gone, a fresh one is needed (spec §7). */
class SourceGone : IOException("HTTP 404")

/** The tiles of one OpenFreeMap planet build (spec §7): [build] like `20261011_080001_pt`, [template] its `tiles` URL. */
data class TileSource(val build: String, val template: String) {
    fun url(t: TileKey): String =
        template.replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())

    companion object {
        private val TEMPLATE = Regex("""https://tiles\.openfreemap\.org/planet/(\d{8}_\d{6}_[a-z]+)/\{z\}/\{x\}/\{y\}\.pbf""")

        /** Null for any address that is not an OpenFreeMap planet build's tiles over https. */
        fun of(template: String): TileSource? = TEMPLATE.matchEntire(template)?.let { TileSource(it.groupValues[1], template) }
    }
}

/** Spec §7: OpenFreeMap's TileJSON, `https://tiles.openfreemap.org/planet`; only its first `tiles` entry is used. */
object TileJson {
    /** Null for anything that is not a TileJSON reaching z14 with an OpenFreeMap build address. */
    fun parse(text: String): TileSource? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val max = (root["maxzoom"] as? JsonPrimitive)?.intOrNull ?: return null
        if (max < TileGrid.MAX_TILE_ZOOM) return null
        val first = ((root["tiles"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return TileSource.of(first)
    }
}
