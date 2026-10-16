package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.inZoneEntry
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.model.zoneEventEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocationRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val state = Membership.compute(genesis.groupId, listOf(genesis.entry, addKid))!!
    private val now = 1_760_000_000_000L
    private val zoneId = ByteArray(16) { 7 }

    private fun verdict(e: DecodedEntry, self: DeviceKeys = momKeys, showOthers: Boolean = true) =
        EntryValidator(genesis.groupId, state, SyncPolicy(self.deviceId, showOthers, today = { TODAY })).check(e, alreadyAccounted = false)

    private fun EntryAuthor.point(lat: Int = 404_168_000, lon: Int = -37_038_000, acc: Int = 20, at: Long = now) =
        locationEntry(lat, lon, acc, at, Location.Provider.GPS)

    private fun EntryAuthor.zone(name: String = "Home", radius: Int = 150, rank: Long = 1, lat: Int = 404_168_000) =
        safeZoneEntry(zoneId, name, lat, -37_038_000, radius, rank)

    @Test
    fun aChildsPointAndZoneReportAreAccepted() {
        assertEquals(EntryKind.LOCATION, kid.point().kind)
        assertEquals(Verdict.Accept, verdict(kid.point()))
        assertEquals(Verdict.Accept, verdict(kid.inZoneEntry(zoneId, now)))
        val exit = kid.zoneEventEntry(zoneId, entered = false, atMs = now).body.state_event!!
        assertEquals(StateEvent.Kind.ZONE_EXIT, exit.kind)
        assertEquals(now, exit.from_ms)
        assertEquals(16, exit.zone_id.size)
    }

    @Test
    fun aMalformedLocationIsSkipped() {
        assertIs<Verdict.Skipped>(verdict(kid.point(lat = LocationRules.MAX_LAT_E7 + 1)))
        assertIs<Verdict.Skipped>(verdict(kid.point(lon = -LocationRules.MAX_LON_E7 - 1)))
        assertIs<Verdict.Skipped>(verdict(kid.point(acc = -1)))
        assertIs<Verdict.Skipped>(verdict(kid.point(at = 0)))
        assertIs<Verdict.Skipped>(verdict(kid.inZoneEntry(ByteArray(15), now)))
        // Spec §8.1: inside a zone no coordinates leave the phone, so a Location carrying both is not a valid one.
        val both = kid.create { it.copy(location = Location(lat_e7 = 1, lon_e7 = 1, fix_time_ms = now, zone_id = zoneId.toByteString())) }
        assertIs<Verdict.Skipped>(verdict(both))
    }

    @Test
    fun aParentsZoneIsAcceptedAndKeptOncePerAuthorAndZone() {
        val e = mom.zone()
        assertEquals(EntryKind.SAFE_ZONE, e.kind)
        assertEquals("${momKeys.deviceId.hex}/zone/${"07".repeat(16)}", e.supersedeKey)
        assertEquals(Verdict.Accept, verdict(e))
    }

    @Test
    fun aMalformedZoneIsSkipped() {
        assertIs<Verdict.Skipped>(verdict(mom.zone(radius = SafeZoneRules.MIN_RADIUS_M - 1)))
        assertIs<Verdict.Skipped>(verdict(mom.zone(radius = SafeZoneRules.MAX_RADIUS_M + 1)))
        assertIs<Verdict.Skipped>(verdict(mom.zone(name = " ")))
        assertIs<Verdict.Skipped>(verdict(mom.zone(name = "x".repeat(65))))
        assertIs<Verdict.Skipped>(verdict(mom.zone(rank = SafeZoneRules.MAX_RANK + 1)))
        assertIs<Verdict.Skipped>(verdict(mom.zone(lat = LocationRules.MAX_LAT_E7 + 1)))
    }

    /** Review Focus 3. */
    @Test
    fun aChildTakesTheParentsZonesButSignsNone() {
        assertIs<Verdict.Skipped>(verdict(kid.zone()), "a child never sets a zone")
        assertIs<Verdict.Skipped>(verdict(mom.point()), "a parent's position is never shared (spec §8)")
        assertEquals(Verdict.Accept, verdict(mom.zone(), self = kidKeys, showOthers = false))
        assertTrue(EntryKind.SAFE_ZONE in SyncPolicy.FROM_PARENTS_TO_ALL)
        assertEquals(Verdict.Unwanted, verdict(mom.summary("2026-10-09", 1), self = kidKeys, showOthers = false))
        // A phone that does not show others (a child) never takes another member's position.
        assertEquals(Verdict.Unwanted, verdict(kid.point(), self = DeviceKeys.generate(), showOthers = false))
    }
}
