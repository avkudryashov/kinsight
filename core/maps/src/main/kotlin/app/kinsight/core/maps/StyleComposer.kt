package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The preview's colours as `#RRGGBB`: [mark] the child's colour, [zone] the accent, [halo] the ring around the point. */
data class OverlayColors(val mark: String, val zone: String, val halo: String)

data class OverlayZone(val latE7: Int, val lonE7: Int, val radiusM: Int) {
    override fun toString() = "OverlayZone"
}

/** Spec §4: the zones, today's route, the last point and its accuracy circle (capped by the caller). */
data class Overlays(val zones: List<OverlayZone>, val route: List<MapPoint>, val last: MapPoint, val accuracyM: Int, val colors: OverlayColors)

/**
 * Offline map spec §1, §4: the bundled Protomaps style has one vector source, [TEMPLATE_SOURCE]; the preview gets one
 * `pmtiles://file://` source per block file, every base layer repeated per source (layer by layer, so the roads of
 * every block lie over the water of every block), and the family's overlays as GeoJSON sources on top.
 */
object StyleComposer {
    const val TEMPLATE_SOURCE = "protomaps"
    private const val ZONES = "kin-zones"
    private const val ACCURACY = "kin-accuracy"
    private const val ROUTE = "kin-route"
    private const val POINT = "kin-point"

    fun sourceId(c: Cell) = "block-${c.x}-${c.y}"

    fun compose(template: String, blocks: Map<Cell, String>, overlays: Overlays): String {
        val style = Json.parseToJsonElement(template).jsonObject
        val cells = blocks.keys.sortedWith(compareBy<Cell>({ it.x }, { it.y }))
        val last = overlays.last
        val sources = buildJsonObject {
            for (c in cells) {
                putJsonObject(sourceId(c)) {
                    put("type", "vector")
                    put("url", "pmtiles://file://" + blocks.getValue(c))
                }
            }
            put(ZONES, geojson(overlays.zones.map { polygon(Geodesy.circle(it.latE7, it.lonE7, it.radiusM.toDouble())) }))
            put(ACCURACY, geojson(listOfNotNull(overlays.accuracyM.takeIf { it > 0 }?.let { polygon(Geodesy.circle(last.latE7, last.lonE7, it.toDouble())) })))
            put(ROUTE, geojson(listOfNotNull(overlays.route.takeIf { it.size > 1 }?.let(::line))))
            put(POINT, geojson(listOf(point(last))))
        }
        val layers = buildJsonArray {
            for (element in style.getValue("layers").jsonArray) {
                val layer = element.jsonObject
                if ((layer["source"] as? JsonPrimitive)?.content != TEMPLATE_SOURCE) {
                    add(layer)
                    continue
                }
                val id = layer.getValue("id").jsonPrimitive.content
                for (c in cells) add(JsonObject(layer + mapOf("id" to JsonPrimitive("$id@${c.name}"), "source" to JsonPrimitive(sourceId(c)))))
            }
            overlayLayers(overlays.colors).forEach { add(it) }
        }
        return JsonObject(style + mapOf("sources" to sources, "layers" to layers)).toString()
    }

    private fun overlayLayers(c: OverlayColors): List<JsonObject> = listOf(
        buildJsonObject {
            put("id", "$ZONES-fill"); put("type", "fill"); put("source", ZONES)
            putJsonObject("paint") { put("fill-color", c.zone); put("fill-opacity", 0.14) }
        },
        buildJsonObject {
            put("id", "$ZONES-line"); put("type", "line"); put("source", ZONES)
            putJsonObject("paint") { put("line-color", c.zone); put("line-opacity", 0.55); put("line-width", 1.5) }
        },
        buildJsonObject {
            put("id", ACCURACY); put("type", "fill"); put("source", ACCURACY)
            putJsonObject("paint") { put("fill-color", c.mark); put("fill-opacity", 0.15) }
        },
        buildJsonObject {
            put("id", ROUTE); put("type", "line"); put("source", ROUTE)
            putJsonObject("layout") { put("line-cap", "round"); put("line-join", "round") }
            putJsonObject("paint") { put("line-color", c.mark); put("line-width", 4) }
        },
        buildJsonObject {
            put("id", POINT); put("type", "circle"); put("source", POINT)
            putJsonObject("paint") {
                put("circle-radius", 6); put("circle-color", c.mark); put("circle-stroke-width", 3); put("circle-stroke-color", c.halo)
            }
        },
    )

    private fun geojson(features: List<JsonObject>) = buildJsonObject {
        put("type", "geojson")
        putJsonObject("data") {
            put("type", "FeatureCollection")
            put("features", JsonArray(features))
        }
    }

    private fun feature(type: String, coordinates: JsonArray) = buildJsonObject {
        put("type", "Feature")
        put("properties", JsonObject(emptyMap()))
        putJsonObject("geometry") {
            put("type", type)
            put("coordinates", coordinates)
        }
    }

    private fun position(lon: Double, lat: Double) = JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat)))

    private fun position(p: MapPoint) = position(p.lonE7 / 1e7, p.latE7 / 1e7)

    private fun polygon(ring: List<LonLat>) = feature("Polygon", JsonArray(listOf(JsonArray(ring.map { position(it.lon, it.lat) }))))

    private fun line(points: List<MapPoint>) = feature("LineString", JsonArray(points.map(::position)))

    private fun point(p: MapPoint) = feature("Point", position(p))
}

/** Spec §4: what the camera takes in, never more than [MapPlanner.NEAR_M] from the last point. */
object PreviewFrame {
    /** The smallest half span around the point: a 20 m fix still shows its street. */
    const val MIN_HALF_M = 150.0

    fun of(last: MapPoint, accuracyM: Int, route: List<MapPoint>, zones: List<OverlayZone>): Bounds {
        val lat = last.latE7 / 1e7
        val lon = last.lonE7 / 1e7
        val limit = Geodesy.boxAround(last.latE7, last.lonE7, MapPlanner.NEAR_M)
        var box = Geodesy.boxAround(last.latE7, last.lonE7, maxOf(MIN_HALF_M, accuracyM.toDouble()))
        for (p in route) {
            if (Geodesy.distanceM(lat, lon, p.latE7 / 1e7, p.lonE7 / 1e7) <= MapPlanner.NEAR_M) box = box.union(Geodesy.boxAround(p.latE7, p.lonE7, 0.0))
        }
        for (z in zones) {
            val edge = Geodesy.distanceM(lat, lon, z.latE7 / 1e7, z.lonE7 / 1e7) - z.radiusM
            if (edge <= MapPlanner.NEAR_M) box = box.union(Geodesy.boxAround(z.latE7, z.lonE7, z.radiusM.toDouble()))
        }
        return box.intersect(limit)
    }
}
