package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals

class GeodesyTest {
    @Test
    fun aZoneCircleIsAClosedRingAtItsRadius() {
        val ring = Geodesy.circle(394_699_000, -3_763_000, 500.0)
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
        for (p in ring) assertEquals(500.0, Geodesy.distanceM(39.4699, -0.3763, p.lat, p.lon), 0.5)
    }

    @Test
    fun aKilometreNorthIsAKilometre() {
        val p = Geodesy.destination(39.4699, -0.3763, 0.0, 1_000.0)
        assertEquals(-0.3763, p.lon, 1e-9)
        assertEquals(1_000.0, Geodesy.distanceM(39.4699, -0.3763, p.lat, p.lon), 0.01)
    }

    @Test
    fun theDistanceToABoxIsZeroInsideAndToTheEdgeOutside() {
        val b = TileGrid.bounds(Cell(510, 389))
        assertEquals(0.0, Geodesy.distanceToBoundsM(39.5, -0.5, b))
        assertEquals(2_124.0, Geodesy.distanceToBoundsM(39.4699, -0.3763, Bounds(39.3, -0.3515625, 39.6, -0.2)), 15.0)
    }

    @Test
    fun aBoxAroundAPointSpansItsRadius() {
        val b = Geodesy.boxAround(395_000_000, -5_000_000, 1_000.0)
        assertEquals(2_000.0, (b.north - b.south) * Geodesy.M_PER_DEG, 0.01)
    }
}
