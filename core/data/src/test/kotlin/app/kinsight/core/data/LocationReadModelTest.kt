package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.inZoneEntry
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.model.zoneEventEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationReadModelTest {
    private val momKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1)
    private val kid = EntryAuthor(gid, kidKeys, lastSeq = 0)
    private val membership = Membership.compute(gid, listOf(genesis.entry, mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)))!!
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 16)
    private val noon = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val min = 60_000L
    private val homeId = ByteArray(16) { 0x0a }
    private val homeHex = "0a".repeat(16)
    private val home = mom.safeZoneEntry(homeId, "Home", 404_168_000, -37_038_000, 150, rank = 1)

    private fun ready(locations: List<DecodedEntry>, events: List<DecodedEntry> = emptyList(), self: DeviceId = momKeys.deviceId) = GroupSnapshot.Ready(
        groupId = gid, self = self, membership = membership, summaries = emptyList(), catalog = emptyList(), events = events,
        heartbeats = emptyList(), peerSeen = emptyMap(), locations = locations, safeZones = listOf(home),
    )

    private fun point(at: Long, lat: Int = 404_000_000) = kid.locationEntry(lat, -37_000_000, 20, at, Location.Provider.GPS)

    @Test
    fun theNewestWordDecidesThePlace() {
        val inZone = kid.inZoneEntry(homeId, noon - 60 * min)
        val exit = kid.zoneEventEntry(homeId, entered = false, atMs = noon - 30 * min)
        val after = point(noon - 30 * min)
        val names = mapOf(homeHex to "Home")
        assertEquals(PlaceStatus.InZone(homeHex, "Home", noon - 60 * min), LocationReadModel.status(listOf(inZone), emptyList(), kidKeys.deviceId, names))
        assertEquals(PlaceStatus.Outside(null, noon - 30 * min), LocationReadModel.status(listOf(inZone), listOf(exit), kidKeys.deviceId, names))
        assertEquals(
            PlaceStatus.Outside(GeoPoint(404_000_000, -37_000_000, 20, noon - 30 * min), noon - 30 * min),
            LocationReadModel.status(listOf(inZone, after), listOf(exit), kidKeys.deviceId, names),
        )
        val enter = kid.zoneEventEntry(homeId, entered = true, atMs = noon - 10 * min)
        assertEquals(PlaceStatus.InZone(homeHex, null, noon - 10 * min), LocationReadModel.status(listOf(inZone, after), listOf(exit, enter), kidKeys.deviceId, emptyMap()))
    }

    @Test
    fun theRouteIsTodaysPointsInTheChildsOrder() {
        val yesterday = point(noon - 20 * 60 * min) // 16:00 UTC the day before
        val first = point(noon - 30 * min, lat = 404_010_000)
        val inZone = kid.inZoneEntry(homeId, noon - 20 * min)
        val second = point(noon - 10 * min, lat = 404_020_000)
        val c = LocationReadModel.childLocation(ready(listOf(yesterday, first, inZone, second)), kidKeys.deviceId, today, utc, noon)!!
        assertEquals(listOf(404_010_000, 404_020_000), c.route.map { it.latE7 })
        assertEquals(404_020_000, c.last!!.latE7)
        assertEquals(listOf("Home"), c.zones.map { it.name })
        assertEquals(null, LocationReadModel.childLocation(ready(emptyList()), momKeys.deviceId, today, utc, noon), "a parent has no location screen")
    }

    @Test
    fun todaysZoneEventsAreListedNewestFirstWithTheirNames() {
        val exit = kid.zoneEventEntry(homeId, entered = false, atMs = noon - 30 * min)
        val enter = kid.zoneEventEntry(homeId, entered = true, atMs = noon - 10 * min)
        val c = LocationReadModel.childLocation(ready(emptyList(), listOf(exit, enter)), kidKeys.deviceId, today, utc, noon)!!
        assertEquals(listOf(StateEvent.Kind.ZONE_ENTER, StateEvent.Kind.ZONE_EXIT), c.zoneEvents.map { it.kind })
        assertEquals("Home", c.zoneEvents.first().zoneName)
    }

    @Test
    fun aParentFollowsAChildOutsideForHalfAnHour() {
        val out = point(noon - 5 * min)
        assertTrue(LocationReadModel.followNeeded(ready(listOf(out)), noon))
        assertFalse(LocationReadModel.followNeeded(ready(listOf(out)), noon + LocationReadModel.FOLLOW_FOR_MS), "the child stopped sending")
        assertFalse(LocationReadModel.followNeeded(ready(listOf(out, kid.inZoneEntry(homeId, noon))), noon))
        assertFalse(LocationReadModel.followNeeded(ready(listOf(out), self = kidKeys.deviceId), noon), "a child never follows")
    }

    @Test
    fun theParentsDashboardCardSaysWhereTheChildIs() {
        val d = SnapshotViews.dashboard(ready(listOf(kid.inZoneEntry(homeId, noon - min))), today, noon)
        assertEquals(PlaceStatus.InZone(homeHex, "Home", noon - min), d.children.single().place)
    }
}
