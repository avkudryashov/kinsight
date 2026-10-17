package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A tile as the server sent it: [gzip] when its Content-Encoding said so (offline map spec §7). */
class Tile(val bytes: ByteArray, val gzip: Boolean)

/** HTTP 404 from OpenFreeMap: the build of the cached TileJSON is gone, a fresh one is needed (spec §7). */
class SourceGone : IOException("HTTP 404")

/**
 * The tiles of one OpenFreeMap planet build (spec §7): [build] like `20261011_080001_pt`, [template] its `tiles` URL.
 * Only [of] builds one, so every instance is an address the map may fetch.
 */
@ConsistentCopyVisibility
data class TileSource private constructor(val build: String, val template: String) {
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

/** Offline map spec §7: OpenFreeMap is the map's only source. */
object MapLinks {
    const val TILE_HOST = "tiles.openfreemap.org"
    const val TILEJSON_URL = "https://$TILE_HOST/planet"
    const val PROJECT_URL = "https://github.com/avkudryashov/kinsight"

    /** Spec §7, OpenFreeMap terms of use: a User-Agent of its own that names the app and its version. */
    fun userAgent(versionName: String): String = "Kinsight/$versionName (+$PROJECT_URL)"
}

/**
 * What a block file holds (the ledger's `installed`, spec §7): the planet [build] it came from and its deepest zoom.
 * [LOCAL] for a debug install, which never ages.
 */
data class BlockVersion(val build: String, val maxZoom: Int) {
    fun encode(): String = "$build/$maxZoom"

    /** The build's day (UTC) in ms; null for a build without a date. */
    fun builtMs(): Long? {
        if (!DATED.matches(build)) return null
        return try {
            LocalDate.parse(build.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        const val LOCAL = "local"
        private val DATED = Regex("""\d{8}_\d{6}_[a-z]+""")
        private val FORM = Regex("""([0-9a-z_]{1,40})/(1[0-4])""")

        /** Null for anything else (a sha256 of the GitHub days, a broken entry): the file counts as unknown. */
        fun parse(s: String?): BlockVersion? = s?.let(FORM::matchEntire)?.let { BlockVersion(it.groupValues[1], it.groupValues[2].toInt()) }
    }
}
