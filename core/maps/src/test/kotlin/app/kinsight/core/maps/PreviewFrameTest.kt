package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Offline map spec §4: the camera takes the point, its accuracy, today's route and the zones near it, within 3 km. */
class PreviewFrameTest {
    private val last = MapPoint(395_000_000, -5_000_000, 0)
    private val kmEastE7 = 116_420 // 1 km of longitude at 39.5° N

    @Test
    fun aLonePointShowsItsStreet() {
        val b = PreviewFrame.of(last, 20, emptyList(), emptyList())
        assertEquals(300.0, (b.north - b.south) * Geodesy.M_PER_DEG, 1.0)
    }

    @Test
    fun theRouteNearbyIsInTheFrame() {
        val b = PreviewFrame.of(last, 20, listOf(MapPoint(395_000_000, -5_000_000 + kmEastE7, 0)), emptyList())
        assertTrue(b.east >= (-5_000_000 + kmEastE7) / 1e7)
    }

    @Test
    fun theRouteTenKilometresAwayIsNot() {
        val b = PreviewFrame.of(last, 20, listOf(MapPoint(395_000_000, -5_000_000 + 10 * kmEastE7, 0)), emptyList())
        assertTrue(b.east < (-5_000_000 + 3 * kmEastE7) / 1e7)
    }

    @Test
    fun aZoneNearbyIsInTheFrameWithItsRadius() {
        val b = PreviewFrame.of(last, 20, emptyList(), listOf(OverlayZone(395_179_660, -5_000_000, 300)))
        assertEquals(39.5179660 + 300 / Geodesy.M_PER_DEG, b.north, 1e-5)
    }

    @Test
    fun aFarZoneIsLeftOut() {
        val b = PreviewFrame.of(last, 20, emptyList(), listOf(OverlayZone(397_000_000, -5_000_000, 300)))
        assertEquals(39.5 + PreviewFrame.MIN_HALF_M / Geodesy.M_PER_DEG, b.north, 1e-6)
    }

    @Test
    fun aVagueFixIsCutAtThreeKilometres() {
        val b = PreviewFrame.of(last, 5_000, emptyList(), emptyList())
        assertEquals(3_000.0, (b.north - 39.5) * Geodesy.M_PER_DEG, 1.0)
    }
}
