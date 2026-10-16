package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.safeZoneEntry
import app.kinsight.core.sync.SafeZoneRules
import kotlin.test.Test
import kotlin.test.assertEquals

class SafeZonesTest {
    private val momKeys = DeviceKeys.generate()
    private val dadKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1)
    private val dad = EntryAuthor(gid, dadKeys, lastSeq = 0, lastLamport = 10)
    private val kid = EntryAuthor(gid, kidKeys, lastSeq = 0, lastLamport = 10)
    private val ops = mutableListOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
        mom.grantAdminEntry(dadKeys.deviceId),
    )
    private val granKeys = DeviceKeys.generate()
    private val gran = EntryAuthor(gid, granKeys, lastSeq = 0, lastLamport = 10)
    private val home = ByteArray(16) { 1 }
    private val homeHex = "01".repeat(16)

    private fun membership() = Membership.compute(gid, ops)!!

    @Test
    fun theHighestRankWinsThenTheAuthor() {
        val first = mom.safeZoneEntry(home, "Home", 404_168_000, -37_038_000, 150, rank = 1)
        val edit = dad.safeZoneEntry(home, "Home", 404_168_000, -37_038_000, 300, rank = SafeZones.nextRank(first))
        assertEquals(listOf(300), SafeZones.effective(listOf(first, edit), membership()).map { it.radiusM })
        val tieMom = EntryAuthor(gid, momKeys, lastSeq = 5).safeZoneEntry(home, "Mom's", 1, 2, 100, rank = 7)
        val tieDad = EntryAuthor(gid, dadKeys, lastSeq = 5).safeZoneEntry(home, "Dad's", 1, 2, 100, rank = 7)
        val winner = if (momKeys.deviceId.hex > dadKeys.deviceId.hex) "Mom's" else "Dad's"
        assertEquals(winner, SafeZones.effective(listOf(tieMom, tieDad), membership()).single().name)
        assertEquals(winner, SafeZones.effective(listOf(tieDad, tieMom), membership()).single().name)
    }

    @Test
    fun aDeletedZoneIsHiddenButKeepsItsName() {
        val first = mom.safeZoneEntry(home, "Home", 1, 2, 150, rank = 1)
        val gone = mom.safeZoneEntry(home, "Home", 1, 2, 150, rank = 2, deleted = true)
        assertEquals(emptyList(), SafeZones.effective(listOf(first, gone), membership()))
        assertEquals(mapOf(homeHex to "Home"), SafeZones.names(listOf(first, gone), membership()))
    }

    @Test
    fun childrenNeverSetAZone() {
        val kids = kid.safeZoneEntry(home, "Kid's", 1, 2, 150, rank = 9)
        val dads = dad.safeZoneEntry(ByteArray(16) { 2 }, "Club", 1, 2, 150, rank = 1)
        assertEquals(listOf("Club"), SafeZones.effective(listOf(kids, dads), membership()).map { it.name })
        assertEquals(mapOf("02".repeat(16) to "Club"), SafeZones.names(listOf(kids, dads), membership()))
    }

    @Test
    fun zonesAndNamesSurviveTheCreatorsLeaving() {
        val club = dad.safeZoneEntry(ByteArray(16) { 2 }, "Club", 1, 2, 150, rank = 1)
        val made = dad.safeZoneEntry(home, "Home", 1, 2, 150, rank = 1)
        val gone = dad.safeZoneEntry(home, "Home", 1, 2, 150, rank = 2, deleted = true)
        ops += dad.leaveGroupEntry() // spec §7.3: the old phone leaves after the move
        val late = dad.safeZoneEntry(ByteArray(16) { 4 }, "Park", 1, 2, 150, rank = 1) // after its cut: never counts
        val all = listOf(club, made, gone, late)
        assertEquals(listOf("Club"), SafeZones.effective(all, membership()).map { it.name }, "the tombstone still hides Home")
        assertEquals(mapOf("02".repeat(16) to "Club", homeHex to "Home"), SafeZones.names(all, membership()))
    }

    @Test
    fun zonesAndNamesSurviveARemovalUpToItsCut() {
        val club = dad.safeZoneEntry(ByteArray(16) { 2 }, "Club", 1, 2, 150, rank = 1)
        val edit = dad.safeZoneEntry(ByteArray(16) { 2 }, "Club 2", 1, 2, 300, rank = 2)
        ops += mom.removeMemberEntry(dadKeys.deviceId, lastSeq = club.seq) // spec §7.4: a lost phone
        assertEquals(listOf(150), SafeZones.effective(listOf(club, edit), membership()).map { it.radiusM }, "the edit is past the cut")
        assertEquals(mapOf("02".repeat(16) to "Club"), SafeZones.names(listOf(club, edit), membership()))
    }

    @Test
    fun aParentWhoIsNotAnAdminSetsNoZone() {
        ops += mom.addMember(granKeys.sign.publicKey, granKeys.dh.publicKey, "Gran", DeviceRole.PARENT)
        val grans = gran.safeZoneEntry(ByteArray(16) { 3 }, "Park", 1, 2, 150, rank = 1)
        assertEquals(emptyList(), SafeZones.effective(listOf(grans), membership()))
        assertEquals(emptyMap(), SafeZones.names(listOf(grans), membership()))
        ops += mom.grantAdminEntry(granKeys.deviceId)
        assertEquals(listOf("Park"), SafeZones.effective(listOf(grans), membership()).map { it.name })
        ops += gran.leaveGroupEntry()
        assertEquals(listOf("Park"), SafeZones.effective(listOf(grans), membership()).map { it.name }, "an admin when it left")
    }

    @Test
    fun theRankSaturatesAtTheCap() {
        val top = mom.safeZoneEntry(home, "Home", 1, 2, 150, rank = SafeZoneRules.MAX_RANK)
        assertEquals(SafeZoneRules.MAX_RANK, SafeZones.nextRank(top))
        assertEquals(1L, SafeZones.nextRank(null))
    }
}
