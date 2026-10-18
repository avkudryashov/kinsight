package app.kinsight.feature.devicedetail

import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.MapPoint
import app.kinsight.core.maps.OverlayColors
import app.kinsight.core.maps.Overlays
import app.kinsight.core.maps.StyleComposer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline map spec §7 «Стиль»: no `http` in the bundled OpenFreeMap styles; every `asset://` link names a bundled file. */
class StyleAssetsTest {
    private val assets = File("src/main/assets")
    private val flavors = listOf("light", "dark")
    private val ranges = listOf("0-255", "256-511", "768-1023", "1024-1279", "1280-1535", "8192-8447")
    private val weights = setOf("NotoSans-Regular", "NotoSans-Bold", "NotoSans-Italic")

    private fun text(flavor: String) = File(assets, "map/style-$flavor.json").readText()

    private fun style(flavor: String) = Json.parseToJsonElement(text(flavor)).jsonObject

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }

    /** The literal icon names of an `icon-image` value; a name built with `concat` is drawn only where it exists. */
    private fun iconNames(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString && e.content.isNotEmpty()) listOf(e.content) else emptyList()
        is JsonArray -> when ((e.firstOrNull() as? JsonPrimitive)?.content) {
            "concat", "get" -> emptyList()
            else -> e.drop(1).flatMap(::iconNames)
        }
        is JsonObject -> emptyList()
    }

    @Test
    fun theStylesNeverReachTheNetwork() {
        for (f in flavors) {
            assertFalse("http" in text(f), f)
            assertFalse("ne2_shaded" in text(f), "$f: the raster relief source is gone")
        }
    }

    @Test
    fun everyAssetLinkPointsAtABundledFile() {
        val used = mutableSetOf<String>()
        for (f in flavors) {
            val s = style(f)
            assertEquals("asset://map/glyphs/{fontstack}/{range}.pbf", s.getValue("glyphs").jsonPrimitive.content)
            assertEquals("asset://map/sprites/ofm", s.getValue("sprite").jsonPrimitive.content)
            val stacks = strings(s.getValue("layers")).filter { it.startsWith("Noto") }.toSet()
            assertTrue(stacks.isNotEmpty() && weights.containsAll(stacks), "$f: stacks without spaces, $stacks")
            used += stacks
            assertEquals(setOf(StyleComposer.TEMPLATE_SOURCE), s.getValue("sources").jsonObject.keys)
        }
        assertEquals(weights, used)
        for (suffix in listOf(".json", ".png", "@2x.json", "@2x.png")) assertTrue(File(assets, "map/sprites/ofm$suffix").isFile, suffix)
        for (stack in weights) for (r in ranges) assertTrue(File(assets, "map/glyphs/$stack/$r.pbf").isFile, "$stack/$r")
        assertTrue(File(assets, "map/glyphs/OFL.txt").isFile)
    }

    @Test
    fun everyNamedIconIsInTheSprite() {
        val sprite = Json.parseToJsonElement(File(assets, "map/sprites/ofm.json").readText()).jsonObject.keys
        for (f in flavors) {
            val icons = style(f).getValue("layers").jsonArray
                .mapNotNull { (it.jsonObject["layout"] as? JsonObject)?.get("icon-image") }.flatMap(::iconNames).toSet()
            assertTrue(icons.isNotEmpty(), f)
            assertEquals(emptySet(), icons - sprite, f)
        }
    }

    @Test
    fun labelsAreBuiltForTheAppLanguage() {
        for (f in flavors) {
            val fields = style(f).getValue("layers").jsonArray.mapNotNull { (it.jsonObject["layout"] as? JsonObject)?.get("text-field") }
            assertTrue(fields.any { it.toString() == """["coalesce",["get","name:ru"],["get","name"]]""" }, f)
            assertTrue(fields.none { "name_en" in it.toString() || "name:latin" in it.toString() }, "$f: name:<lang>, else name (spec §7)")
        }
    }

    @Test
    fun theComposedPreviewStaysOffline() {
        val last = MapPoint(395_000_000, -5_000_000, 0)
        val composed = StyleComposer.compose(
            text("light"),
            mapOf(Cell(510, 389) to "/data/user/0/app.kinsight/files/maps/z10-510-389.pmtiles"),
            Overlays(emptyList(), listOf(last), last, 20, OverlayColors("#000000", "#000000", "#FFFFFF")),
            "es",
        )
        assertFalse("http" in composed)
        assertTrue("pmtiles://file:///data/user/0/app.kinsight/files/maps/z10-510-389.pmtiles" in composed)
        assertTrue("name:es" in composed)
    }
}
