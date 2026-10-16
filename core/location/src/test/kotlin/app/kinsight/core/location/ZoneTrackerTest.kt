package app.kinsight.core.location

import app.kinsight.core.model.proto.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneTrackerTest {
    private val home = Zone("aa".repeat(16), 404_168_000, -37_038_000, 150)
    private val district = Zone("bb".repeat(16), 404_168_000, -37_038_000, 2_000)
    private val now = 1_760_000_000_000L

    // 0.001 degree of latitude is about 111 m.
    private fun fixAt(latE7: Int, accuracy: Int = 10) = Fix(latE7, -37_038_000, accuracy, now, Location.Provider.GPS)
    private val atHome = fixAt(404_168_000)
    private val farAway = fixAt(404_168_000 - 500_000) // about 5.6 km south

    @Test
    fun distancesAreMetres() {
        val d = Geo.distanceM(404_168_000, -37_038_000, 404_178_000, -37_038_000)
        assertTrue(d in 110.0..112.5, "$d")
        assertEquals(0.0, Geo.distanceM(1, 2, 1, 2))
        assertTrue(Geo.distanceM(0, 1_799_999_999, 0, -1_799_999_999) < 30.0, "across the antimeridian")
    }

    @Test
    fun theFirstFixIsABaselineWithoutEvents() {
        val s = ZoneTracker.fix(TrackerState(), listOf(home), atHome, now)
        assertEquals(Mode.IN_ZONE, s.state.mode)
        assertEquals(setOf(home.id), s.state.inside)
        assertTrue(s.events.isEmpty())
        assertEquals(Report.InZone(home.id, now), s.report)
    }

    @Test
    fun aFixFarAwayLeavesTheZoneWithAPoint() {
        val s = ZoneTracker.fix(TrackerState(Mode.IN_ZONE, setOf(home.id)), listOf(home), farAway, now)
        assertEquals(listOf(ZoneEvent(home.id, entered = false, atMs = now)), s.events)
        assertEquals(Report.Point(farAway), s.report)
        assertEquals(Mode.OUTSIDE, s.state.mode)
    }

    @Test
    fun aFixJustOutsideWithinItsAccuracyKeepsTheZone() {
        val edge = fixAt(404_168_000 + 15_000, accuracy = 50) // about 167 m north: beyond 150 m, within 150 + 50
        val s = ZoneTracker.fix(TrackerState(Mode.IN_ZONE, setOf(home.id)), listOf(home), edge, now)
        assertTrue(s.events.isEmpty())
        assertEquals(Report.InZone(home.id, now), s.report)
    }

    @Test
    fun anImpreciseFixDecidesNothingButStillGoesOutAsAPoint() {
        val vague = fixAt(404_168_000, accuracy = 1_000)
        val out = ZoneTracker.fix(TrackerState(Mode.OUTSIDE), listOf(home), vague, now)
        assertEquals(Mode.OUTSIDE, out.state.mode)
        assertTrue(out.events.isEmpty())
        assertEquals(Report.Point(vague), out.report)
        val first = ZoneTracker.fix(TrackerState(), listOf(home), vague, now)
        assertEquals(TrackerState(), first.state, "no baseline from a vague first fix")
        assertNull(first.report)
    }

    @Test
    fun inTwoZonesTheSmallerIsReportedAndLeavingOneKeepsTheCoordinatesHome() {
        val both = ZoneTracker.fix(TrackerState(Mode.OUTSIDE), listOf(home, district), atHome, now)
        assertEquals(setOf(home.id, district.id), both.state.inside)
        assertEquals(2, both.events.count { it.entered })
        assertEquals(Report.InZone(home.id, now), both.report)
        val left = ZoneTracker.proximity(both.state, listOf(home, district), home.id, entering = false, nowMs = now, fix = farAway)
        assertEquals(listOf(ZoneEvent(home.id, entered = false, atMs = now)), left.events)
        assertNull(left.report, "still in a zone: no coordinates leave the phone")
        assertEquals(Mode.IN_ZONE, left.state.mode)
    }

    @Test
    fun proximityAlertsAreDeduplicatedAndUnknownZonesIgnored() {
        val inside = TrackerState(Mode.IN_ZONE, setOf(home.id))
        assertTrue(ZoneTracker.proximity(inside, listOf(home), home.id, entering = true, nowMs = now, fix = null).events.isEmpty())
        val stranger = ZoneTracker.proximity(inside, listOf(home), "cc".repeat(16), entering = false, nowMs = now, fix = null)
        assertTrue(stranger.events.isEmpty())
        assertEquals(inside, stranger.state)
    }

    @Test
    fun aDeletedZoneIsDroppedWithoutAnExit() {
        val s = ZoneTracker.fix(TrackerState(Mode.IN_ZONE, setOf(home.id)), emptyList(), null, now)
        assertTrue(s.events.isEmpty())
        assertTrue(s.state.inside.isEmpty())
        assertEquals(Mode.OUTSIDE, s.state.mode)
    }

    @Test
    fun withoutZonesEveryFixIsAPoint() {
        val s = ZoneTracker.fix(TrackerState(), emptyList(), atHome, now)
        assertEquals(Mode.OUTSIDE, s.state.mode)
        assertEquals(Report.Point(atHome), s.report)
    }

    @Test
    fun fixesAndZonesNeverPrintTheirCoordinates() {
        assertFalse("404" in atHome.toString())
        assertFalse("404" in home.toString())
        assertEquals(home.id, ZoneTracker.idOf(home.signature))
    }
}
