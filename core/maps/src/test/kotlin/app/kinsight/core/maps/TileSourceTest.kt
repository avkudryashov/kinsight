package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** OpenFreeMap spec §7: the tile address comes from the TileJSON, and only an OpenFreeMap planet build is taken. */
class TileSourceTest {
    private val real = """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf"],""" +
        """"vector_layers":[],"maxzoom":14,"minzoom":0,"name":"OpenFreeMap"}"""

    @Test
    fun theRealTileJsonGivesTheBuildAndItsAddress() {
        val s = assertNotNull(TileJson.parse(real))
        assertEquals("20261011_080001_pt", s.build)
        assertEquals("https://tiles.openfreemap.org/planet/20261011_080001_pt/14/8170/6234.pbf", s.url(TileKey(14, 8170, 6234)))
    }

    /** Review Focus 2: a TileJSON that names another host, plain http or a path that is no build is refused. */
    @Test
    fun aTileAddressElsewhereIsRefused() {
        listOf(
            "http://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf",
            "https://evil.example/planet/20261011_080001_pt/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org.evil.example/planet/20261011_080001_pt/{z}/{x}/{y}.pbf",
            "https://user@tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf",
            "https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf?key=1",
            "https://tiles.openfreemap.org/planet/20261011_080001_pt/../{z}/{x}/{y}.pbf",
        ).forEach { assertNull(TileSource.of(it), it) }
    }

    @Test
    fun aTileJsonWithoutZ14OrBrokenIsRefused() {
        assertNull(TileJson.parse(real.replace("\"maxzoom\":14", "\"maxzoom\":13")))
        assertNull(TileJson.parse(real.replace(",\"maxzoom\":14", "")))
        assertNull(TileJson.parse("""{"tiles":[],"maxzoom":14}"""))
        assertNull(TileJson.parse("{not json"))
        assertNull(TileJson.parse("[]"))
    }

    @Test
    fun theUserAgentNamesTheAppAndItsVersion() =
        assertEquals("Kinsight/0.8.3 (+https://github.com/avkudryashov/kinsight)", MapLinks.userAgent("0.8.3"))
}
