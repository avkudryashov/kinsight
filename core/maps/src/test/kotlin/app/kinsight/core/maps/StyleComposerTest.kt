package app.kinsight.core.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Offline map spec §1, §4: one source per block file, the base layers repeated per source, the overlays on top. */
class StyleComposerTest {
    private val template = """{"version":8,"glyphs":"asset://map/glyphs/{fontstack}/{range}.pbf","sprite":"asset://map/sprites/light",""" +
        """"sources":{"openmaptiles":{"type":"vector","url":"pmtiles://file://BLOCK"}},"layers":[""" +
        """{"id":"background","type":"background","paint":{"background-color":"#cccccc"}},""" +
        """{"id":"earth","type":"fill","source":"openmaptiles","source-layer":"earth"},""" +
        """{"id":"roads","type":"line","source":"openmaptiles","source-layer":"roads"}]}"""
    private val colors = OverlayColors(mark = "#E8590C", zone = "#2F6FEB", halo = "#FFFFFF")
    private val last = MapPoint(395_000_000, -5_000_000, 0)
    private val overlays = Overlays(listOf(OverlayZone(395_010_000, -5_010_000, 150)), listOf(MapPoint(394_990_000, -4_990_000, 0), last), last, 25, colors)
    private val blocks = mapOf(Cell(511, 389) to "/data/maps/z10-511-389.pmtiles", Cell(510, 389) to "/data/maps/z10-510-389.pmtiles")

    private fun composed(o: Overlays = overlays) = Json.parseToJsonElement(StyleComposer.compose(template, blocks, o, "ru")).jsonObject

    private fun layer(id: String) = composed().getValue("layers").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }

    private fun features(source: String, o: Overlays = overlays) =
        composed(o).getValue("sources").jsonObject.getValue(source).jsonObject.getValue("data").jsonObject.getValue("features").jsonArray

    @Test
    fun everyBlockIsASourceOfItsOwnFile() {
        val sources = composed().getValue("sources").jsonObject
        assertEquals(setOf("block-510-389", "block-511-389", "kin-zones", "kin-accuracy", "kin-route", "kin-point"), sources.keys)
        assertEquals("pmtiles://file:///data/maps/z10-510-389.pmtiles", sources.getValue("block-510-389").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun theLayersRepeatPerBlockAndTheOverlaysComeLast() = assertEquals(
        listOf(
            "background", "earth@z10-510-389", "earth@z10-511-389", "roads@z10-510-389", "roads@z10-511-389",
            "kin-zones-fill", "kin-zones-line", "kin-accuracy", "kin-route", "kin-point",
        ),
        composed().getValue("layers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
    )

    /** GeoJSON order is lon, lat: a swap would put every overlay in the wrong place. */
    @Test
    fun thePointIsWrittenLonThenLat() {
        val coordinates = features("kin-point").single().jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(listOf(-0.5, 39.5), coordinates.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun theOverlaysAreInTheChildsColour() {
        assertEquals("#E8590C", layer("kin-point").getValue("paint").jsonObject.getValue("circle-color").jsonPrimitive.content)
        assertEquals("#E8590C", layer("kin-route").getValue("paint").jsonObject.getValue("line-color").jsonPrimitive.content)
        assertEquals("#2F6FEB", layer("kin-zones-fill").getValue("paint").jsonObject.getValue("fill-color").jsonPrimitive.content)
    }

    @Test
    fun aZoneIsAClosedGeodesicRing() {
        val ring = features("kin-zones").single().jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.single().jsonArray
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun aSinglePointDrawsNoRoute() = assertEquals(0, features("kin-route", overlays.copy(route = listOf(last))).size)

    @Test
    fun nothingPointsAtTheNetwork() = assertFalse("http" in StyleComposer.compose(template, blocks, overlays, "ru"))

    private val labelled = """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"pmtiles://file://BLOCK"}},"layers":[""" +
        """{"id":"place","type":"symbol","source":"openmaptiles","source-layer":"place",""" +
        """"layout":{"text-field":["coalesce",["get","name:ru"],["get","name"]]}},""" +
        """{"id":"shield","type":"symbol","source":"openmaptiles","source-layer":"transportation_name",""" +
        """"layout":{"text-field":["to-string",["get","ref"]]}}]}"""

    private fun textField(language: String, id: String) =
        Json.parseToJsonElement(StyleComposer.compose(labelled, blocks, overlays, language)).jsonObject.getValue("layers").jsonArray
            .map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == "$id@z10-510-389" }
            .getValue("layout").jsonObject.getValue("text-field").toString()

    /** OpenFreeMap spec §7: labels in the app's language where the map has it, else the local name. */
    @Test
    fun labelsFollowTheAppLanguage() {
        assertEquals("""["coalesce",["get","name:es"],["get","name"]]""", textField("es", "place"))
        assertEquals("""["coalesce",["get","name:ru"],["get","name"]]""", textField("ru", "place"))
        assertEquals("""["coalesce",["get","name:en"],["get","name"]]""", textField("de", "place"))
        assertEquals("""["to-string",["get","ref"]]""", textField("es", "shield"))
    }
}
