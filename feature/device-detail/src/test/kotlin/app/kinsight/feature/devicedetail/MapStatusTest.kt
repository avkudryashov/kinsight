package app.kinsight.feature.devicedetail

import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.EventView
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.LocationReadModel
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.PlaceStatus
import app.kinsight.core.data.Role
import app.kinsight.core.data.SetupWarning
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ZoneView
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class MapStatusTest {
    private val now = 1_760_000_000_000L
    private val min = 60_000L
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val home = ZoneView("aa".repeat(16), "Home", 404_168_000, -37_038_000, 150, DeviceId("aa".repeat(16)))
    private val school = ZoneView("bb".repeat(16), "School", 404_500_000, -37_038_000, 200, DeviceId("aa".repeat(16)))

    private fun loc(
        status: PlaceStatus?,
        last: GeoPoint? = null,
        events: List<EventView> = emptyList(),
        sharing: Sharing = Sharing.On,
        warnings: Set<SetupWarning> = emptySet(),
    ) = ChildLocation(kid, status, last, listOfNotNull(last), listOf(home, school), events, sharing, warnings)

    private fun zoneEvent(seq: Long, kind: StateEvent.Kind, at: Long, zone: ZoneView = home) =
        EventView(kid.id, seq, kind, at, 0, at, 0, zoneId = zone.id, zoneName = zone.name)

    @Test
    fun inAZoneSinceTodaysEntry() {
        val events = listOf(zoneEvent(9, StateEvent.Kind.ZONE_ENTER, now - 40 * min), zoneEvent(8, StateEvent.Kind.ZONE_EXIT, now - 90 * min))
        assertEquals(MapStatus.InZone("Home", now - 40 * min), MapStatus.of(loc(PlaceStatus.InZone(home.id, "Home", now - 5 * min), events = events), true, now))
    }

    @Test
    fun inAZoneEnteredBeforeTodayHasNoSince() {
        val events = listOf(zoneEvent(8, StateEvent.Kind.ZONE_EXIT, now - 90 * min, school))
        assertEquals(MapStatus.InZone("Home", null), MapStatus.of(loc(PlaceStatus.InZone(home.id, "Home", now - 5 * min), events = events), true, now))
    }

    @Test
    fun anExitNewerThanTheEntryGivesNoSince() {
        val events = listOf(zoneEvent(9, StateEvent.Kind.ZONE_EXIT, now - 10 * min), zoneEvent(8, StateEvent.Kind.ZONE_ENTER, now - 60 * min))
        assertEquals(MapStatus.InZone("Home", null), MapStatus.of(loc(PlaceStatus.InZone(home.id, "Home", now - min), events = events), true, now))
    }

    @Test
    fun outsideNamesTheNearestZoneAndTheDistanceToItsEdge() {
        // 0.005° north of Home's centre: about 556 m, minus its 150 m radius, in 10 m steps.
        val p = GeoPoint(404_218_000, -37_038_000, 10, now - 2 * min)
        assertEquals(MapStatus.Outside("Home", 410), MapStatus.of(loc(PlaceStatus.Outside(p, p.timeMs), p), true, now))
    }

    /** Review Focus 2: never «0 м от»; the screen says «у границы». */
    @Test
    fun aPointInsideTheCircleIsAtTheEdge() {
        val p = GeoPoint(404_168_000, -37_038_000, 10, now - 2 * min)
        assertEquals(MapStatus.Outside("Home", 0), MapStatus.of(loc(PlaceStatus.Outside(p, p.timeMs), p), true, now))
    }

    @Test
    fun anExitWithoutAFixUsesTheLastPoint() {
        val p = GeoPoint(404_218_000, -37_038_000, 10, now - 20 * min)
        assertEquals(MapStatus.Outside("Home", 410), MapStatus.of(loc(PlaceStatus.Outside(null, now - 2 * min), p), true, now))
    }

    /** Task 12: without any zone «Вне зон» says nothing; the status says the zones are not set, with the point's age. */
    @Test
    fun withoutAnyZoneTheZonesAreNotSet() {
        val p = GeoPoint(404_218_000, -37_038_000, 10, now - 3 * min)
        val noZones = ChildLocation(kid, PlaceStatus.Outside(p, p.timeMs), p, listOf(p), emptyList(), emptyList(), Sharing.On)
        assertEquals(MapStatus.NoZones(p.timeMs), MapStatus.of(noZones, true, now))
        val exit = ChildLocation(kid, PlaceStatus.Outside(null, now - 2 * min), p, listOf(p), emptyList(), emptyList(), Sharing.On)
        assertEquals(MapStatus.NoZones(now - 2 * min), MapStatus.of(exit, true, now))
    }

    @Test
    fun anOldWordWithoutZonesIsStillTheLastPointAgo() {
        val at = now - LocationReadModel.PLACE_STALE_MS - min
        val p = GeoPoint(404_218_000, -37_038_000, 10, at)
        val noZones = ChildLocation(kid, PlaceStatus.Outside(p, at), p, listOf(p), emptyList(), emptyList(), Sharing.On)
        assertEquals(MapStatus.Stale(at), MapStatus.of(noZones, true, now))
    }

    @Test
    fun anOldWordIsTheLastPointAgo() {
        val at = now - LocationReadModel.PLACE_STALE_MS - min
        assertEquals(MapStatus.Stale(at), MapStatus.of(loc(PlaceStatus.InZone(home.id, "Home", at)), true, now))
    }

    @Test
    fun noPlaceSaysWhyCloudThenSharingThenPermissionThenTheSwitch() {
        assertEquals(MapStatus.NoPlace(NoPlaceReason.CLOUD_OFF), MapStatus.of(loc(null, sharing = Sharing.Never), false, now))
        assertEquals(MapStatus.NoPlace(NoPlaceReason.NOT_SHARING), MapStatus.of(loc(null, sharing = Sharing.Paused(1)), true, now))
        val both = setOf(SetupWarning.LOCATION_PERMISSION_OFF, SetupWarning.LOCATION_DISABLED)
        assertEquals(MapStatus.NoPlace(NoPlaceReason.PERMISSION_OFF), MapStatus.of(loc(null, warnings = both), true, now))
        assertEquals(MapStatus.NoPlace(NoPlaceReason.LOCATION_DISABLED), MapStatus.of(loc(null, warnings = setOf(SetupWarning.LOCATION_DISABLED)), true, now))
        assertEquals(MapStatus.NoPlace(NoPlaceReason.WAITING), MapStatus.of(loc(null), true, now))
    }

    @Test
    fun distancesRoundToTensThenHundreds() {
        assertEquals(0, MapStatus.roundDistance(4.9))
        assertEquals(350, MapStatus.roundDistance(347.0))
        assertEquals(1_200, MapStatus.roundDistance(1_234.0))
    }
}
