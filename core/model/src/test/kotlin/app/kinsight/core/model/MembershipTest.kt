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
        assertConsistent(expected)
    }

    private fun assertConsistent(state: MembershipState) {
        assertTrue(state.active.keys.none { it in state.departed })
        for (e in state.effectiveEntries) {
            val d = state.departed[e.author]
            if (d != null) assertTrue(e.seq <= d.lastSeq, "$e beyond cutoff ${d.lastSeq}")
            else assertTrue(e.author in state.active, "$e author unknown")
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

    @Test
    fun voidedRemovalDoesNotLeavePhantomCutoff() {
        val g = TestGroup()
        val qKeys = DeviceKeys.generate()
        val aliceKeys = DeviceKeys.generate()
        val add1 = g.creator.addMember(qKeys)
        val add2 = g.creator.addMember(aliceKeys)
        val promote = g.creator.setAdmin(qKeys.deviceId, true)
        val rogue = g.newAuthor(qKeys).removeMember(aliceKeys.deviceId, lastSeq = 0)
        val demote = g.creator.setAdmin(qKeys.deviceId, false, lastSeq = 0)
        val leave = g.newAuthor(aliceKeys).leave()
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add1, add2, promote, rogue, demote, leave))!!
        assertFalse(rogue in state.effectiveEntries)
        assertFalse(aliceKeys.deviceId in state.active)
        assertEquals(1L, state.departed.getValue(aliceKeys.deviceId).lastSeq)
        assertTrue(leave in state.effectiveEntries)
        assertConsistent(state)
    }

    @Test
    fun voidedDemotionDoesNotLeavePhantomAdminCutoff() {
        val g = TestGroup()
        val qKeys = DeviceKeys.generate()
        val wKeys = DeviceKeys.generate()
        val entries = mutableListOf(g.genesis.entry)
        entries += g.creator.addMember(qKeys)
        entries += g.creator.addMember(wKeys)
        entries += g.creator.setAdmin(qKeys.deviceId, true)
        entries += g.creator.setAdmin(wKeys.deviceId, true)
        entries += g.newAuthor(qKeys).setAdmin(wKeys.deviceId, false, lastSeq = 0)
        entries += g.creator.setAdmin(qKeys.deviceId, false, lastSeq = 0)
        val wAdd = g.newAuthor(wKeys).addMember(DeviceKeys.generate(), "later")
        entries += wAdd
        val state = Membership.compute(g.groupId, entries)!!
        assertTrue(state.active.getValue(wKeys.deviceId).isAdmin)
        assertTrue(wAdd in state.effectiveEntries)
        assertConsistent(state)
    }

    @Test
    fun demotingNonAdminDoesNotBlockFirstPromotion() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val noop = g.creator.setAdmin(bobKeys.deviceId, false, lastSeq = 0)
        val promote = g.creator.setAdmin(bobKeys.deviceId, true)
        val bobAdd = g.newAuthor(bobKeys).addMember(DeviceKeys.generate(), "x")
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, noop, promote, bobAdd))!!
        assertTrue(state.active.getValue(bobKeys.deviceId).isAdmin)
        assertTrue(promote in state.effectiveEntries)
        assertTrue(bobAdd in state.effectiveEntries)
    }

    @Test
    fun cycleOnlyRemovalStaysConsistent() {
        val g = TestGroup()
        val q = DeviceKeys.generate()
        val r = DeviceKeys.generate()
        val z = DeviceKeys.generate()
        val x = DeviceKeys.generate()
        val entries = mutableListOf(g.genesis.entry)
        for (k in listOf(q, r, z, x)) entries += g.creator.addMember(k) // P seq 2..5
        for (k in listOf(q, r, z, x)) entries += g.creator.setAdmin(k.deviceId, true) // P seq 6..9
        g.clock.now = 1_500
        entries += g.creator.removeMember(z.deviceId, lastSeq = 5) // P seq 10
        g.clock.now = 1_600
        val x1 = g.newAuthor(x).addMember(DeviceKeys.generate(), "w")
        entries += x1
        g.clock.now = 2_000
        val qa = g.newAuthor(q)
        entries += qa.removeMember(r.deviceId, lastSeq = 1) // Q seq 1 @2001
        entries += g.newAuthor(r).removeMember(g.creatorKeys.deviceId, lastSeq = 9) // R seq 1 @2002
        entries += qa.removeMember(z.deviceId, lastSeq = 5) // Q seq 2 @2003
        g.clock.now = 2_500
        entries += g.newAuthor(z).removeMember(x.deviceId, lastSeq = 0) // Z seq 1 @2501
        g.clock.now = 3_000
        entries += g.creator.removeMember(q.deviceId, lastSeq = 0) // P seq 11
        val state = Membership.compute(g.groupId, entries)!!
        assertConsistent(state)
        val rnd = Random(3)
        repeat(10) { assertEquals(state, Membership.compute(g.groupId, entries.shuffled(rnd))) }
        // Z's removal of X is effective under the settled cutoffs, so X is departed and x1 is void.
        assertTrue(x.deviceId in state.departed)
        assertFalse(x1 in state.effectiveEntries)
    }

    @Test
    fun laterRemovalLowersDepartedCutoff() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val add = g.creator.addMember(bobKeys)
        val first = g.creator.removeMember(bobKeys.deviceId, lastSeq = 5)
        val second = g.creator.removeMember(bobKeys.deviceId, lastSeq = 2)
        val state = Membership.compute(g.groupId, listOf(g.genesis.entry, add, first, second))!!
        assertEquals(2L, state.departed.getValue(bobKeys.deviceId).lastSeq)
        assertFalse(state.acceptsEntryFrom(bobKeys.deviceId, 3))
    }

    @Test
    fun creatorRemovedWithLastSeqZeroKeepsGenesis() {
        val g = TestGroup()
        val bobKeys = DeviceKeys.generate()
        val entries = mutableListOf(g.genesis.entry)
        entries += g.creator.addMember(bobKeys) // P seq 2
        entries += g.creator.setAdmin(bobKeys.deviceId, true) // P seq 3
        entries += g.newAuthor(bobKeys).removeMember(g.creatorKeys.deviceId, lastSeq = 0)
        val state = Membership.compute(g.groupId, entries)!!
        assertConsistent(state)
        assertTrue(g.genesis.entry in state.effectiveEntries)
        assertEquals(1L, state.departed.getValue(g.creatorKeys.deviceId).lastSeq)
        assertTrue(state.acceptsEntryFrom(g.creatorKeys.deviceId, 1))
        assertFalse(state.acceptsEntryFrom(g.creatorKeys.deviceId, 2))
        val rnd = Random(11)
        repeat(5) { assertEquals(state, Membership.compute(g.groupId, entries.shuffled(rnd))) }
    }

    @Test
    fun randomGroupsAreConsistentAndOrderIndependent() {
        val baseSeed = 20261009L
        repeat(1500) { i ->
            val seed = baseSeed + i
            val rnd = Random(seed)
            val g = TestGroup()
            val others = List(rnd.nextInt(2, 6)) { DeviceKeys.generate() } // 3..6 devices with the creator
            val all = listOf(g.creatorKeys) + others
            val authors = mutableMapOf(g.creatorKeys.deviceId to g.creator)
            fun author(k: DeviceKeys) = authors.getOrPut(k.deviceId) { g.newAuthor(k) }
            val entries = mutableListOf(g.genesis.entry)
            for (k in others) entries += g.creator.addMember(k)
            for (k in others) if (rnd.nextInt(3) > 0) entries += g.creator.setAdmin(k.deviceId, true)
            repeat(rnd.nextInt(3, 11)) {
                // Mostly forward, sometimes backdated timestamps.
                g.clock.now += if (rnd.nextInt(4) == 0) -rnd.nextLong(1, 20) else rnd.nextLong(0, 3)
                val a = author(all.random(rnd))
                val target = all.random(rnd).deviceId
                entries += when (rnd.nextInt(5)) {
                    0, 1 -> a.removeMember(target, lastSeq = rnd.nextLong(0, 12))
                    2 -> a.setAdmin(target, rnd.nextBoolean(), lastSeq = rnd.nextLong(0, 12))
                    3 -> a.addMember(DeviceKeys.generate(), "n")
                    else -> a.leave()
                }
            }
            val state = assertNotNull(Membership.compute(g.groupId, entries), "seed=$seed")
            try {
                assertConsistent(state)
                for (e in state.effectiveEntries) {
                    assertTrue(state.acceptsEntryFrom(e.author, e.seq), "effective $e not accepted")
                }
                repeat(3) { assertEquals(state, Membership.compute(g.groupId, entries.shuffled(rnd))) }
            } catch (t: AssertionError) {
                throw AssertionError("seed=$seed: ${t.message}", t)
            }
        }
    }
}
