package app.kinsight.core.model

import app.kinsight.core.model.proto.SignedEntry
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembershipTest {
    @Test
    fun genesisOnlyMakesCreatorAdmin() {
        val g = TestGroup()
        val state = assertNotNull(Membership.compute(g.groupId, listOf(g.genesis.entry)))
        assertEquals(setOf(g.creatorKeys.deviceId), state.active.keys)
        assertTrue(state.active.getValue(g.creatorKeys.deviceId).isAdmin)
    }

    @Test
    fun genesisForAnotherGroupIsRejected() {
        val g = TestGroup()
        val other = TestGroup()
        assertNull(Membership.compute(other.groupId, listOf(g.genesis.entry)))
    }

    @Test
    fun adminAddsMemberNonAdminCannot() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys, "bob")
        val bob = g.newAuthor(bobKeys)
        val bobAddsEve = bob.addMember(DeviceKeys.generate(), "eve")
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, bobAddsEve))!!
        assertEquals(setOf(g.creatorKeys.deviceId, bobKeys.deviceId), state.active.keys)
        assertFalse(state.active.getValue(bobKeys.deviceId).isAdmin)
        assertTrue(add in state.effectiveEntries)
        assertFalse(bobAddsEve in state.effectiveEntries)
    }

    @Test
    fun removeWinsOverLaterAdd() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add1 = g.creator.addMember(bobKeys)
        val remove = g.creator.removeMember(bobKeys.deviceId, lastSeq = 0)
        val add2 = g.creator.addMember(bobKeys)
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add1, remove, add2))!!
        assertFalse(bobKeys.deviceId in state.active)
        assertEquals(0L, state.departed.getValue(bobKeys.deviceId).lastSeq)
    }

    @Test
    fun removeBeforeAddInTimeStillWins() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val remove = g.creator.removeMember(bobKeys.deviceId, lastSeq = 0)
        val add = g.creator.addMember(bobKeys)
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, remove, add))!!
        assertFalse(bobKeys.deviceId in state.active)
        assertNull(state.signKeyOf(bobKeys.deviceId))
    }

    @Test
    fun removedMemberCutoffApplies() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val remove = g.creator.removeMember(bobKeys.deviceId, lastSeq = 5)
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, remove))!!
        assertTrue(state.acceptsEntryFrom(bobKeys.deviceId, 5))
        assertFalse(state.acceptsEntryFrom(bobKeys.deviceId, 6))
        assertContentEquals(bobKeys.sign.publicKey, state.signKeyOf(bobKeys.deviceId))
    }

    @Test
    fun demotedAdminBackdatedOpsAreVoid() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val promote = g.creator.setAdmin(bobKeys.deviceId, true)
        val bob = g.newAuthor(bobKeys)
        val legit = bob.addMember(DeviceKeys.generate(), "legit")         // bob seq 1
        val demote = g.creator.setAdmin(bobKeys.deviceId, false, lastSeq = 1)
        // Timestamps so far: genesis 1001, add 1002, promote 1003, legit 1004, demote 1005.
        // Bob backdates his next op to 1004, i.e. before the demotion in replay order.
        g.clock.now = 1_003
        val evil = bob.addMember(DeviceKeys.generate(), "evil")           // bob seq 2
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, promote, legit, demote, evil))!!
        assertTrue(legit in state.effectiveEntries)
        assertFalse(evil in state.effectiveEntries)
        assertEquals(3, state.active.size) // creator, bob, legit
    }

    @Test
    fun leaveMovesMemberToDeparted() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val bob = g.newAuthor(bobKeys)
        bob.create { it } // seq 1, unrelated
        val left = bob.leave() // seq 2
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, left))!!
        assertFalse(bobKeys.deviceId in state.active)
        assertEquals(2L, state.departed.getValue(bobKeys.deviceId).lastSeq)
    }

    @Test
    fun activeMemberByDhKey() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, g.creator.addMember(bobKeys)))!!
        assertEquals(bobKeys.deviceId, state.activeMemberByDhKey(bobKeys.dh.publicKey)?.id)
        assertNull(state.activeMemberByDhKey(DeviceKeys.generate().dh.publicKey))
    }

    @Test
    fun hashIgnoresOrderAndUnrelatedEntries() {
        val g = TestGroup()
        val a = g.creator.addMember(DeviceKeys.generate())
        val b = g.creator.addMember(DeviceKeys.generate())
        val unrelated = g.creator.create { it }
        assertContentEquals(
            Membership.hash(g.groupId, listOf(g.genesis.entry, a, b)),
            Membership.hash(g.groupId, listOf(b, unrelated, a, g.genesis.entry)),
        )
    }

    @Test
    fun orderOfArrivalDoesNotMatter() = runTest {
        checkAll(30, Arb.long()) { seed ->
            val rnd = Random(seed)
            val g = TestGroup()
            val entries = mutableListOf(g.genesis.entry)
            val members = mutableListOf<DeviceKeys>()
            repeat(rnd.nextInt(2, 6)) {
                val k = DeviceKeys.generate()
                members += k
                entries += g.creator.addMember(k)
            }
            repeat(rnd.nextInt(0, 4)) {
                val k = members.random(rnd)
                when (rnd.nextInt(3)) {
                    0 -> entries += g.creator.removeMember(k.deviceId, rnd.nextLong(0, 3))
                    1 -> entries += g.creator.setAdmin(k.deviceId, rnd.nextBoolean(), rnd.nextLong(0, 3))
                    else -> entries += g.newAuthor(k).leave()
                }
            }
            val expected = Membership.compute(g.groupId, entries)
            repeat(5) {
                assertEquals(expected, Membership.compute(g.groupId, entries.shuffled(rnd)))
            }
        }
    }

    private fun junk(e: DecodedEntry): DecodedEntry {
        val signed = SignedEntry.ADAPTER.decode(e.bytes)
        return DecodedEntry.decode(SignedEntry.ADAPTER.encode(signed.copy(signature = ByteArray(64).toByteString())))
    }

    @Test
    fun junkSignatureCopyCannotDeleteRemove() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val remove = g.creator.removeMember(bobKeys.deviceId, lastSeq = 0)
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, remove, junk(remove)))!!
        assertFalse(bobKeys.deviceId in state.active)
        assertTrue(remove in state.effectiveEntries)
    }

    @Test
    fun junkSignatureGenesisCopyIsIgnored() {
        val g = TestGroup()
        val add = g.creator.addMember(DeviceKeys.generate())
        val clean = Membership.compute(g.groupId, listOf(g.genesis.entry, add))
        val dirty = Membership.compute(g.groupId, listOf(junk(g.genesis.entry), g.genesis.entry, add))
        assertNotNull(dirty)
        assertEquals(clean, dirty)
    }

    @Test
    fun hashIgnoresJunkCopies() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val remove = g.creator.removeMember(bobKeys.deviceId, lastSeq = 0)
        val clean = listOf(g.genesis.entry, add, remove)
        val dirty = clean + listOf(junk(remove), junk(g.genesis.entry), junk(add))
        assertContentEquals(Membership.hash(g.groupId, clean), Membership.hash(g.groupId, dirty))
    }

    @Test
    fun cyclicRemovalsTerminateAndAreOrderIndependent() {
        val g = TestGroup()
        val qKeys = DeviceKeys.generate()
        val rKeys = DeviceKeys.generate()
        val entries = mutableListOf(g.genesis.entry)
        entries += g.creator.addMember(qKeys)
        entries += g.creator.addMember(rKeys)
        entries += g.creator.setAdmin(qKeys.deviceId, true)
        entries += g.creator.setAdmin(rKeys.deviceId, true)
        g.clock.now = 2_000
        entries += g.newAuthor(qKeys).removeMember(rKeys.deviceId, lastSeq = 1) // Q seq1 @2001
        entries += g.newAuthor(rKeys).removeMember(g.creatorKeys.deviceId, lastSeq = 5) // R seq1 @2002
        g.clock.now = 3_000
        entries += g.creator.removeMember(qKeys.deviceId, lastSeq = 0) // P seq6 @3001
        val expected = Membership.compute(g.groupId, entries)!!
        val rnd = Random(7)
        repeat(20) { assertEquals(expected, Membership.compute(g.groupId, entries.shuffled(rnd))) }
        for (e in expected.effectiveEntries) {
            val d = expected.departed[e.author] ?: continue
            assertTrue(e.seq <= d.lastSeq, "$e beyond cutoff ${d.lastSeq}")
        }
    }

    @Test
    fun demotionIsPermanent() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val promote1 = g.creator.setAdmin(bobKeys.deviceId, true)
        val bob = g.newAuthor(bobKeys)
        val legit = bob.addMember(DeviceKeys.generate(), "legit") // bob seq1
        val demote = g.creator.setAdmin(bobKeys.deviceId, false, lastSeq = 1)
        val promote2 = g.creator.setAdmin(bobKeys.deviceId, true)
        val later = bob.addMember(DeviceKeys.generate(), "later") // bob seq2
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, promote1, legit, demote, promote2, later))!!
        assertFalse(state.active.getValue(bobKeys.deviceId).isAdmin)
        assertTrue(legit in state.effectiveEntries)
        assertFalse(promote2 in state.effectiveEntries)
        assertFalse(later in state.effectiveEntries)
    }

    @Test
    fun addWithDuplicateDhKeyIsRejected() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val clone = DeviceKeys(sign = DeviceKeys.generate().sign, dh = bobKeys.dh)
        val state = Membership.compute(
            g.groupId,
            listOf(g.genesis.entry, g.creator.addMember(bobKeys), g.creator.addMember(clone)),
        )!!
        assertEquals(setOf(g.creatorKeys.deviceId, bobKeys.deviceId), state.active.keys)
    }
}
