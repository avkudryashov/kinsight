package app.kinsight.feature.devicedetail

import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.ZoneView
import app.kinsight.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapProjectionTest {
    private val w = 400f
    private val h = 300f
    private val pad = 20f

    @Test
    fun everythingFitsInsideThePaddingWithNorthUpAndEastRight() {
        val home = Triple(404_168_000, -37_038_000, 200)
        val east = Triple(404_168_000, -36_920_000, 0) // about 1 km east
        val p = MapProjection.fit(listOf(home, east), w, h, pad)!!
        val r = p.px(200.0)
        assertTrue(p.x(home.second) - r >= pad - 0.5f && p.x(east.second) <= w - pad + 0.5f)
        assertTrue(p.x(east.second) > p.x(home.second), "east is right")
        val north = p.y(404_178_000)
        assertTrue(north < p.y(404_168_000), "north is up")
    }

    @Test
    fun aSinglePointGetsTheMinimumSpan() {
        val p = MapProjection.fit(listOf(Triple(0, 0, 0)), w, h, pad)!!
        assertEquals(MapProjection.MIN_SPAN_M / (h - 2 * pad), p.metersPerPx, 1e-9)
    }

    @Test
    fun theScaleBarIsARoundLengthWithinAQuarterOfTheWidth() {
        val p = MapProjection.fit(listOf(Triple(0, 0, 0), Triple(0, 90_000, 0)), w, h, pad)!! // about 1 km wide
        val m = p.scaleBarMeters(w)
        assertTrue(m in MapProjection.STEPS)
        assertTrue(p.px(m.toDouble()) <= w / 4)
    }

    @Test
    fun nothingToShowIsNull() {
        assertNull(MapProjection.fit(emptyList(), w, h, pad))
        assertNull(MapProjection.fit(listOf(Triple(0, 0, 0)), 30f, 30f, pad))
    }

    @Test
    fun theLastPointsAccuracyHaloFitsInsideThePadding() {
        val home = ZoneView("aa".repeat(16), "Home", 404_168_000, -37_038_000, 150, DeviceId("aa".repeat(16)))
        val route = listOf(GeoPoint(404_168_000, -37_030_000, 10, 1L))
        val last = GeoPoint(404_168_000, -37_020_000, 120, 2L) // the east edge of everything
        val p = MapProjection.fit(mapCircles(listOf(home), route, last), w, h, pad)!!
        val halo = p.px(last.accuracyM.toDouble())
        assertTrue(p.x(last.lonE7) + halo <= w - pad + 0.5f, "halo inside on the right")
        assertTrue(p.y(last.latE7) - halo >= pad - 0.5f && p.y(last.latE7) + halo <= h - pad + 0.5f, "halo inside vertically")
    }

    @Test
    fun aVagueLastPointDoesNotZoomTheMapOut() {
        val last = GeoPoint(0, 0, 50_000, 1L)
        assertEquals(MAX_HALO_M, mapCircles(emptyList(), emptyList(), last).single().third)
    }
}
