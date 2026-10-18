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
        EntryValidator(genesis.groupId, state, SyncPolicy(self.deviceId, showOthers, today = { TODAY }, nowMs = { now })).check(e, alreadyAccounted = false)

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

    @Test
    fun anOldLocationExpiresOnReceipt() {
        val v = EntryValidator(genesis.groupId, state, SyncPolicy(momKeys.deviceId, today = { TODAY }, nowMs = { now }))
        assertEquals(Verdict.Expired, v.check(kid.point(at = now - SyncPolicy.LOCATION_RETENTION_MS - 1), alreadyAccounted = false))
        assertEquals(Verdict.Accept, v.check(kid.point(at = now - 60_000), alreadyAccounted = false))
    }

    /**
     * A point the receiver already accounted for and pruned since (or took and let expire) comes again from a peer: it is a
     * Duplicate, so the session's seen set and vector see nothing new, not Expired.
     */
    @Test
    fun aPrunedPointReceivedAgainIsADuplicate() {
        val v = EntryValidator(genesis.groupId, state, SyncPolicy(momKeys.deviceId, today = { TODAY }, nowMs = { now }))
        val old = kid.point(at = now - SyncPolicy.LOCATION_RETENTION_MS - 1)
        assertEquals(Verdict.Duplicate, v.check(old, alreadyAccounted = true))
        assertEquals(Verdict.Expired, v.check(old, alreadyAccounted = false))
    }

    /** Y4 parked minor: the caps a hostile peer might try, each skipped for its own reason. */
    @Test
    fun theCapsHold() {
        fun reason(e: DecodedEntry) = (verdict(e) as Verdict.Skipped).reason
        // Body caps: the size check comes first, so an oversized body is refused as such.
        val bigPoint = kid.create { it.copy(location = Location(fix_time_ms = now, zone_id = ByteArray(LocationRules.MAX_BODY_BYTES).toByteString())) }
        assertTrue(bigPoint.bodySize > LocationRules.MAX_BODY_BYTES)
        assertEquals("location over ${LocationRules.MAX_BODY_BYTES} bytes", reason(bigPoint))
        val bigZone = mom.zone(name = "x".repeat(SafeZoneRules.MAX_BODY_BYTES))
        assertTrue(bigZone.bodySize > SafeZoneRules.MAX_BODY_BYTES)
        assertEquals("safe zone over ${SafeZoneRules.MAX_BODY_BYTES} bytes", reason(bigZone))
        // uint32 accuracy of 2^31 and above reads negative here.
        assertEquals("accuracy out of range", reason(kid.point(acc = (1L shl 31).toInt())))
        assertEquals("accuracy out of range", reason(kid.point(acc = -1))) // 2^32 - 1 on the wire
        assertEquals(Verdict.Accept, verdict(kid.point(acc = Int.MAX_VALUE)))
        // A zone id is exactly 16 bytes on SAFE_ZONE too.
        for (size in listOf(0, 15, 17)) {
            val z = mom.safeZoneEntry(ByteArray(size) { 7 }, "Home", 404_168_000, -37_038_000, 150, 1)
            assertEquals("zone id of $size bytes", reason(z))
        }
        assertEquals("centre out of range", reason(mom.safeZoneEntry(zoneId, "Home", 0, LocationRules.MAX_LON_E7 + 1, 150, 1)))
    }
}
