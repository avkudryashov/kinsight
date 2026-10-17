package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.safeZoneEntry
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a child's phone gets over Bluetooth (spec §10, §8.1, R5): exactly the parents' [SyncPolicy.FROM_PARENTS_TO_ALL]
 * entries, never anyone's usage or locations; and a second session with nothing new sends nothing.
 */
class ChildDeliveryTest {
    private val now = System.currentTimeMillis()

    /** Node 0 and 1 are parents, the rest children; a child has showOthers off, as SyncPolicies sets it. */
    private suspend fun family(size: Int = 3): List<Node> =
        buildGroup(size, showOthers = { it < 2 }, role = { if (it < 2) DeviceRole.PARENT else DeviceRole.CHILD })

    private fun EntryAuthor.pin(rank: Long = 1) = parentPinEntry(ByteArray(16) { 1 }, ByteArray(32) { 2 }, 19_456, 2, rank)

    private fun EntryAuthor.zone() = safeZoneEntry(ByteArray(16) { 7 }, "Home", 404_168_000, -37_038_000, 150, rank = 1)

    private fun EntryAuthor.access(): DecodedEntry = create {
        it.copy(
            cloud_access = CloudAccess(
                generation = 1, storage_id = ByteArray(16).toByteString(),
                nonce = ByteArray(12).toByteString(), ciphertext = ByteArray(80).toByteString(),
            ),
        )
    }

    private fun EntryAuthor.point() = locationEntry(404_168_000, -37_038_000, 20, now, Location.Provider.GPS)

    private suspend fun Node.holds(e: DecodedEntry) = e in store.liveEntries()

    @Test
    fun aParentsPinReachesTheChildOverBluetooth() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        val pin = mom.author.pin()
        mom.publish(pin)
        val (rm, rk) = syncPair(mom, kid)
        assertIs<SessionResult.Completed>(rm)
        assertEquals(1, assertIs<SessionResult.Completed>(rk).received)
        assertTrue(kid.holds(pin), "the child must hold the parent's PIN after a Bluetooth session")
    }

    @Test
    fun aSafeZoneAndCloudAccessReachTheChildButNotTheParentsOwnData() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        val zone = mom.author.zone()
        val access = mom.author.access()
        val usage = mom.author.summary(TODAY.toString(), 1)
        val point = mom.author.point()
        mom.publish(usage, zone, point, access)
        syncPair(kid, mom)
        assertTrue(kid.holds(zone), "safe zone")
        assertTrue(kid.holds(access), "cloud access")
        assertTrue(!kid.holds(usage) && !kid.holds(point), "a parent's usage and location stay off the child")
        assertEquals(EntryKind.SAFE_ZONE, zone.kind)
        assertEquals(EntryKind.CLOUD_ACCESS, access.kind)
    }

    @Test
    fun aSiblingsUsageAndLocationNeverReachAChild() = runBlocking<Unit> {
        val (mom, _, kid, sib) = family(4)
        val usage = sib.author.summary(TODAY.toString(), 5)
        val point = sib.author.point()
        sib.publish(usage, point)
        val pin = mom.author.pin()
        mom.publish(pin)
        syncPair(sib, mom)
        assertTrue(mom.holds(usage) && mom.holds(point), "the parent takes the child's data")
        syncPair(mom, kid)
        syncPair(sib, kid) // a sibling relays the parents' PIN, never its own data
        assertTrue(kid.holds(pin))
        assertTrue(kid.store.liveEntries().none { it.author == sib.keys.deviceId && !it.isMembershipRelevant }, "nothing of the sibling")
        assertTrue(sib.store.liveEntries().none { it.author == kid.keys.deviceId && !it.isMembershipRelevant }, "nor the other way")
    }

    @Test
    fun aChildSentNothingNewGetsNoBatch() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        mom.publish(mom.author.pin(), mom.author.zone())
        syncPair(mom, kid)
        val (rm, rk) = syncPair(mom, kid)
        assertEquals(0, assertIs<SessionResult.Completed>(rm).sentBatches, "the PIN and zone again")
        assertEquals(0, assertIs<SessionResult.Completed>(rk).received)
        // The parent's own usage moves its log past them: still nothing for the child, and its position follows.
        mom.publish(mom.author.summary(TODAY.toString(), 3))
        val (rm2, _) = syncPair(mom, kid)
        assertEquals(0, assertIs<SessionResult.Completed>(rm2).sentBatches)
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
        val (rm3, _) = syncPair(mom, kid)
        assertEquals(0, assertIs<SessionResult.Completed>(rm3).sentBatches)
        // A new PIN after that still gets through.
        val newer = mom.author.pin(rank = 2)
        mom.publish(newer)
        syncPair(kid, mom)
        assertTrue(kid.holds(newer))
    }

    @Test
    fun aPinRelayedByTheOtherParentReachesTheChildAndTheAuthorClosesTheGap() = runBlocking<Unit> {
        val (mom, dad, kid) = family()
        mom.publish(mom.author.summary(TODAY.toString(), 1)) // a seq the child never takes, below the PIN
        val pin = mom.author.pin()
        mom.publish(pin)
        syncPair(mom, dad)
        syncPair(dad, kid)
        assertTrue(kid.holds(pin), "relayed by the other parent")
        // A relay cannot make the child skip a seq of mom's: the usage seq below the PIN stays a gap.
        assertTrue(kid.store.versionVector()[mom.keys.deviceId] < pin.seq)
        assertTrue(kid.store.isAccounted(mom.keys.deviceId, pin.seq))
        // Mom herself closes it.
        syncPair(mom, kid)
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
        val (rd, _) = syncPair(dad, kid)
        assertEquals(0, assertIs<SessionResult.Completed>(rd).sentBatches, "dad sends mom's PIN no more")
    }

    @Test
    fun aProtocolTwoChildGetsNoBatchAndStillCompletes() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        mom.publish(mom.author.pin(), mom.author.summary(TODAY.toString(), 1))
        val (rm, rk) = syncPair(mom, kid, versionsB = 2..2)
        assertEquals(0, assertIs<SessionResult.Completed>(rm).sentBatches)
        assertEquals(2, assertIs<SessionResult.Completed>(rk).protocolVersion)
        assertEquals(0, kid.store.versionVector()[mom.keys.deviceId], "no completion from a peer not obliged to send")
        val (rm2, rk2) = syncPair(mom, kid, versionsA = 2..2, legacyB = true, versionsB = 2..2)
        assertIs<SessionResult.Completed>(rm2)
        assertIs<SessionResult.Completed>(rk2)
        assertTrue(kid.store.liveEntries().none { it.author == mom.keys.deviceId && !it.isMembershipRelevant })
    }
}
