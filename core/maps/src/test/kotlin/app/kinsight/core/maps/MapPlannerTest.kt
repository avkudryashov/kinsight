package app.kinsight.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapPlannerTest {
    private val now = 1_760_000_000_000L
    private val valencia = Cell(510, 389)

    @Test
    fun aPointMarksItsCellWithItsTime() =
        assertEquals(mapOf(valencia to now - 5), MapPlanner.touch(emptyMap(), listOf(MapPoint(395_000_000, -5_000_000, now - 5)), now))

    @Test
    fun aMarkNeverGoesBackInTime() =
        assertEquals(mapOf(valencia to now), MapPlanner.touch(mapOf(valencia to now), listOf(MapPoint(395_000_000, -5_000_000, now - 99)), now))

    @Test
    fun aWeekOldMarkIsDropped() = assertEquals(emptyMap(), MapPlanner.touch(mapOf(valencia to now - MapPlanner.KEEP_MS), emptyList(), now))

    @Test
    fun aHundredMegabytesStaySpare() {
        assertTrue(MapPlanner.hasRoom(10L + (100L shl 20), 10L))
        assertFalse(MapPlanner.hasRoom(9L + (100L shl 20), 10L))
    }

    @Test
    fun aClockThatWentBackAsksForTheCatalogAgain() {
        assertTrue(MapPlanner.catalogDue(null, now))
        assertFalse(MapPlanner.catalogDue(now - 1, now))
        assertTrue(MapPlanner.catalogDue(now - MapPlanner.CATALOG_EVERY_MS, now))
        assertTrue(MapPlanner.catalogDue(now + 60_000, now))
    }
}
