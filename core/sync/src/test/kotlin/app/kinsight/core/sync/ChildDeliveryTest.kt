package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.AppRule
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
        kid.publish(kid.author.summary(TODAY.toString(), 9))
        val (rm, rk) = syncPair(mom, kid)
        assertEquals(1, assertIs<SessionResult.Completed>(rm).received)
        assertEquals(false, assertIs<SessionResult.Completed>(rk).peerAckUnchanged, "the child's usage moved mom's vector")
        // The PIN lands above the child's gap in mom's log (membership seqs, never accounted there): its ack is unchanged,
        // and mom's completion at Done closes the gap. Delivered all the same; resending would only repeat it.
        assertTrue(rm.peerAckUnchanged)
        assertEquals(1, assertIs<SessionResult.Completed>(rk).received)
        assertTrue(kid.holds(pin), "the child must hold the parent's PIN after a Bluetooth session")
    }

    @Test
    fun childTakesAppRulesAndCategoryOverrides() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        val rule = mom.author.appRuleEntry(kid.keys.deviceId.bytes(), AppRule.Target.APP, "com.example.game", mode = AppRule.Mode.BLOCK, rank = 1)
        val override = mom.author.categoryOverride("com.example.game", 1)
        val usage = mom.author.summary(TODAY.toString(), 1)
        mom.publish(usage, rule, override)
        syncPair(mom, kid)
        assertTrue(kid.holds(rule), "the child enforces the parent's rule")
        assertTrue(kid.holds(override), "and computes the same category for a category rule")
        assertTrue(!kid.holds(usage), "never the parent's usage")
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
    fun aParentRelaysTheOtherParentsShareButNeverTheirUsageOrLocation() = runBlocking<Unit> {
        val (mom, dad, kid) = family()
        val usage = dad.author.summary(TODAY.toString(), 4)
        val point = dad.author.point()
        val pin = dad.author.pin()
        dad.publish(usage, pin)
        syncPair(dad, mom)
        // A parent's point is refused on receipt (LocationRules); put it on mom's phone anyway, as a relay that held it.
        mom.publish(point)
        assertTrue(mom.holds(usage) && mom.holds(point) && mom.holds(pin))
        syncPair(mom, kid)
        assertEquals(listOf(pin), kid.store.liveEntries().filter { it.author == dad.keys.deviceId && !it.isMembershipRelevant })
    }

    @Test
    fun aCompletionOnlySessionRecordsTheVectorsItLeaves() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        mom.publish(mom.author.summary(TODAY.toString(), 3)) // nothing the child takes, but it moves mom's log
        val (rm, rk) = syncPair(mom, kid)
        val m = assertIs<SessionResult.Completed>(rm)
        val k = assertIs<SessionResult.Completed>(rk)
        assertEquals(0, m.sentBatches + k.sentBatches)
        // The next session starts from exactly these: «nothing new» holds after one session, not two.
        val momNow = mom.store.versionVector().canonicalBytes()
        val kidNow = kid.store.versionVector().canonicalBytes()
        assertTrue(m.ourVector.contentEquals(momNow) && m.peerVector.contentEquals(kidNow), "mom's record")
        assertTrue(k.ourVector.contentEquals(kidNow) && k.peerVector.contentEquals(momNow), "the child's record")
    }

    @Test
    fun aShareKindAnOlderChildDropsStaysOpenUntilItsUpdate() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        // An older build: it does not take PINs yet (Unwanted), so it must not complete mom's log over the PIN.
        kid.policy = kid.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL - EntryKind.PARENT_PIN)
        val pin = mom.author.pin()
        mom.publish(mom.author.summary(TODAY.toString(), 1), pin, mom.author.summary(TODAY.minusDays(1).toString(), 2))
        syncPair(mom, kid)
        assertTrue(!kid.holds(pin))
        assertEquals(pin.seq - 1, kid.store.versionVector()[mom.keys.deviceId], "completed up to the PIN, not over it")
        assertTrue(!kid.store.isAccounted(mom.keys.deviceId, pin.seq))
        // After the update the PIN arrives and mom's log completes.
        kid.policy = kid.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL)
        syncPair(mom, kid)
        assertTrue(kid.holds(pin))
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
    }

    /**
     * Spec §8: a protocol 3 child (its three share kinds) drops a parent's APP_RULE and CATEGORY_OVERRIDE as Unwanted;
     * [SyncSession] holds the parent's log open below the first of them, so both arrive after the child's update.
     */
    @Test
    fun anOlderChildHoldsBackRulesAndOverridesUntilItsUpdate() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        kid.policy = kid.policy.copy(shareKinds = setOf(EntryKind.CLOUD_ACCESS, EntryKind.SAFE_ZONE, EntryKind.PARENT_PIN))
        val rule = mom.author.appRuleEntry(kid.keys.deviceId.bytes(), AppRule.Target.APP, "com.example.game", mode = AppRule.Mode.BLOCK, rank = 1)
        val override = mom.author.categoryOverride("com.example.chat", 1)
        val pin = mom.author.pin()
        mom.publish(rule, override, pin, mom.author.summary(TODAY.toString(), 1))
        val (_, rk) = syncPair(mom, kid, versionsB = 2..3)
        assertEquals(3, assertIs<SessionResult.Completed>(rk).protocolVersion)
        assertTrue(kid.holds(pin), "the kinds it knows still arrive")
        assertTrue(!kid.holds(rule) && !kid.holds(override))
        assertEquals(rule.seq - 1, kid.store.versionVector()[mom.keys.deviceId], "completed up to the rule, not over it")
        assertTrue(!kid.store.isAccounted(mom.keys.deviceId, rule.seq) && !kid.store.isAccounted(mom.keys.deviceId, override.seq))
        kid.policy = kid.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL)
        syncPair(mom, kid)
        assertTrue(kid.holds(rule) && kid.holds(override))
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
    }

    /** A future share kind the child's build already knows (HEARTBEAT plays it): the older child must not complete over it. */
    @Test
    fun aKnownKindAnOlderChildDropsStaysOpenAndTheResendIsNothingNew() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        mom.policy = mom.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL + EntryKind.HEARTBEAT)
        mom.publish(mom.author.summary(TODAY.toString(), 1))
        val hb = mom.heartbeat()
        mom.publish(mom.author.summary(TODAY.minusDays(1).toString(), 2))
        repeat(2) { round ->
            val (rm, rk) = syncPair(mom, kid)
            assertEquals(1, assertIs<SessionResult.Completed>(rm).sentBatches, "the heartbeat, again")
            assertEquals(0, assertIs<SessionResult.Completed>(rk).received)
            // The resend moved nothing on the child: «nothing new» for the transport, not a session every cooldown.
            assertTrue(assertIs<SessionResult.Completed>(rm).peerAckUnchanged)
            assertTrue(rk.peerAckUnchanged)
            assertEquals(hb.seq - 1, kid.store.versionVector()[mom.keys.deviceId], "open below the heartbeat")
            // Mom records the vector the child announced (it sent a batch, so it cannot predict the child's completion).
            // From the second session on that is what the child advertises: «nothing new» holds at the transport.
            if (round == 1) assertTrue(rm.peerVector.contentEquals(kid.store.versionVector().canonicalBytes()), "mom's record of the child")
            assertTrue(rm.ourVector.contentEquals(mom.store.versionVector().canonicalBytes()), "mom's own record")
        }
        kid.policy = kid.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL + EntryKind.HEARTBEAT)
        syncPair(mom, kid)
        assertTrue(kid.holds(hb))
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
    }

    /** A share kind the child's build does not know at all: it decodes as UNKNOWN there and stays open too. */
    @Test
    fun anUnknownKindAnOlderChildDropsStaysOpen() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        mom.policy = mom.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL + EntryKind.UNKNOWN)
        // EntryBody field 30 (length-delimited, holding field 1 = 1): a payload this build does not know.
        val future = mom.author.create {
            it.copy(unknownFields = okio.Buffer().writeByte(0xF2).writeByte(0x01).writeByte(2).writeByte(0x08).writeByte(0x01).readByteString())
        }
        assertEquals(EntryKind.UNKNOWN, future.kind)
        mom.publish(future, mom.author.summary(TODAY.toString(), 1))
        syncPair(mom, kid)
        assertTrue(!kid.holds(future))
        assertEquals(future.seq - 1, kid.store.versionVector()[mom.keys.deviceId])
        assertTrue(!kid.store.isAccounted(mom.keys.deviceId, future.seq))
        kid.policy = kid.policy.copy(shareKinds = SyncPolicy.FROM_PARENTS_TO_ALL + EntryKind.UNKNOWN)
        syncPair(mom, kid)
        assertTrue(kid.holds(future))
        assertEquals(mom.store.versionVector()[mom.keys.deviceId], kid.store.versionVector()[mom.keys.deviceId])
    }

    @Test
    fun aSkippedShareEntryStaysOpenOnTheChild() = runBlocking<Unit> {
        val (mom, _, kid) = family()
        // A PIN the child's rules refuse (another build's cost range), held by its author.
        val odd = mom.author.parentPinEntry(ByteArray(16) { 1 }, ByteArray(32) { 2 }, ParentPinRules.MEMORY_KIB.last + 1, 2, 1)
        mom.publish(odd, mom.author.summary(TODAY.toString(), 1))
        syncPair(mom, kid)
        assertTrue(!kid.holds(odd))
        assertEquals(odd.seq - 1, kid.store.versionVector()[mom.keys.deviceId])
        assertTrue(!kid.store.isAccounted(mom.keys.deviceId, odd.seq))
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
