package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Offline map spec §2, §3: the z10 cell of a point (Web Mercator, as `pmtiles extract` cuts it) and its 3 km neighbours. */
class TileGridTest {
    private val valencia = Cell(510, 389)

    @Test
    fun valenciaIsInItsCell() = assertEquals(valencia, TileGrid.cellOf(394_699_000, -3_763_000))

    @Test
    fun theSouthernHemisphereCountsDownwards() = assertEquals(Cell(942, 614), TileGrid.cellOf(-338_688_000, 1_512_093_000))

    @Test
    fun aCellsBoundsMatchTheTileScheme() {
        val b = TileGrid.bounds(valencia)
        assertEquals(-0.703125, b.west, 1e-9)
        assertEquals(-0.3515625, b.east, 1e-9)
        assertEquals(39.36827914916013, b.south, 1e-9)
        assertEquals(39.639537564366705, b.north, 1e-9)
    }

    @Test
    fun theNameIsTheFileName() = assertEquals("z10-510-389", valencia.name)

    @Test
    fun aPointOnTheEdgeBelongsToTheEastCellAndSeesTheWestOne() {
        assertEquals(Cell(511, 389), TileGrid.cellOf(395_000_000, -3_515_625))
        assertEquals(setOf(Cell(511, 389), valencia), TileGrid.cellsNear(395_000_000, -3_515_625, 3_000.0))
    }

    @Test
    fun twoKilometresFromTheEastEdgeAddsTheEastNeighbour() =
        assertEquals(setOf(valencia, Cell(511, 389)), TileGrid.cellsNear(394_699_000, -3_763_000, 3_000.0))

    @Test
    fun nearTheNorthEastCornerAllFourCells() = assertEquals(
        setOf(valencia, Cell(510, 388), Cell(511, 389), Cell(511, 388)),
        TileGrid.cellsNear(396_300_000, -3_600_000, 3_000.0),
    )

    @Test
    fun deepInsideOnlyItsOwnCell() = assertEquals(setOf(valencia), TileGrid.cellsNear(395_000_000, -5_000_000, 3_000.0))

    @Test
    fun theNeighbourhoodIsTheCellAndItsEightNeighbours() {
        val around = (509..511).flatMap { x -> (388..390).map { y -> Cell(x, y) } }.toSet()
        assertEquals(around, TileGrid.neighbourhood(valencia))
        assertEquals(around, TileGrid.neighbourhood(396_300_000, -3_600_000), "near a corner: the same nine, no extra side")
        assertEquals(setOf(Cell(0, 0), Cell(1, 0), Cell(0, 1), Cell(1, 1)), TileGrid.neighbourhood(Cell(0, 0)), "off the grid is left out")
    }

    @Test
    fun thePolesAndTheAntimeridianStayOnTheGrid() {
        assertEquals(Cell(0, 0), TileGrid.cellOf(900_000_000, -1_800_000_000))
        assertEquals(Cell(1023, 1023), TileGrid.cellOf(-900_000_000, 1_800_000_000))
    }

    /** OpenFreeMap spec §7: a neighbour to z13 is 85 tiles, the centre to z14 is 341, a neighbourhood 1 021. */
    @Test
    fun aCellHas85TilesToZ13And341ToZ14() {
        assertEquals(85, TileGrid.tiles(valencia, 13).size)
        assertEquals(341, TileGrid.tiles(valencia, 14).size)
        assertEquals(85, TileGrid.tileCount(13))
        assertEquals(341, TileGrid.tileCount(14))
        assertEquals(1_021, TileGrid.tileCount(14) + 8 * TileGrid.tileCount(13))
    }

    @Test
    fun everyTileLiesInsideItsCellZoomByZoom() {
        val tiles = TileGrid.tiles(valencia, 14)
        assertEquals(TileKey(10, 510, 389), tiles.first())
        for (t in tiles) {
            val shift = t.z - TileGrid.ZOOM
            assertEquals(valencia, Cell(t.x shr shift, t.y shr shift))
        }
        assertEquals(tiles.size, tiles.toSet().size)
        assertEquals(listOf(10, 11, 12, 13, 14), tiles.map { it.z }.distinct())
    }

    @Test
    fun aDepthOutsideZ10ToZ14IsRefused() {
        assertFailsWith<IllegalArgumentException> { TileGrid.tiles(valencia, 9) }
        assertFailsWith<IllegalArgumentException> { TileGrid.tiles(valencia, 15) }
    }

    @Test
    fun aTileKeyIsNeverLogged() = assertEquals("TileKey", TileKey(14, 8160, 6230).toString())
}
