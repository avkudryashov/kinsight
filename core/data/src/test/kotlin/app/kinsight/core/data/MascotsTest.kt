package app.kinsight.core.data

import app.kinsight.core.model.Animal
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.Mood
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MascotsTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)

    private class Family(val keys: List<DeviceKeys>, val entries: List<DecodedEntry>)

    private fun family(n: Int): Family {
        val keys = List(n) { DeviceKeys.generate() }
        val adds = keys.mapIndexed { i, k -> mom.addMember(k.sign.publicKey, k.dh.publicKey, "m$i", DeviceRole.CHILD) }
        return Family(keys, listOf(genesis.entry) + adds)
    }

    private fun state(entries: List<DecodedEntry>): MembershipState = Membership.compute(gid, entries)!!

    @Test
    fun resultDoesNotDependOnTheArrivalOrder() {
        val f = family(6)
        val a = Mascots.assign(gid, state(f.entries))
        repeat(5) {
            assertEquals(a, Mascots.assign(gid, state(f.entries.shuffled())))
        }
        assertEquals(7, a.size)
    }

    @Test
    fun aNewcomerChangesNoOneElse() {
        val f = family(4)
        val before = Mascots.assign(gid, state(f.entries))
        val k = DeviceKeys.generate()
        val add = mom.addMember(k.sign.publicKey, k.dh.publicKey, "new", DeviceRole.CHILD)
        val after = Mascots.assign(gid, state(f.entries + add))
        assertEquals(before, after.filterKeys { it in before })
        assertEquals(before.size + 1, after.size)
    }

    @Test
    fun aDepartedMemberKeepsItsAnimalAndNobodyTakesIt() {
        val f = family(3)
        val before = Mascots.assign(gid, state(f.entries))
        val gone = f.keys[1].deviceId
        val remove = mom.removeMemberEntry(gone, lastSeq = 0)
        val k = DeviceKeys.generate()
        val add = mom.addMember(k.sign.publicKey, k.dh.publicKey, "new", DeviceRole.CHILD)
        val after = Mascots.assign(gid, state(f.entries + remove + add))
        assertEquals(before, after.filterKeys { it in before })
        assertNotEquals(after.getValue(gone), after.getValue(k.deviceId))
        assertEquals(5, after.values.toSet().size)
    }

    @Test
    fun tenMembersGetTenDifferentAnimalsAndAnEleventhRepeats() {
        val ten = Mascots.assign(gid, state(family(9).entries))
        assertEquals(10, ten.size)
        assertEquals(10, ten.values.toSet().size)
        val eleven = Mascots.assign(gid, state(family(10).entries))
        assertEquals(11, eleven.size)
        assertTrue(eleven.values.toSet().size == 10)
    }

    @Test
    fun moodThresholds() {
        val h = 100L
        assertEquals(Mood.HAPPY, Mascots.moodOf(0, h))
        assertEquals(Mood.HAPPY, Mascots.moodOf(2490, 10_000))
        assertEquals(Mood.CONTENT, Mascots.moodOf(2500, 10_000))
        assertEquals(Mood.CALM, Mascots.moodOf(7490, 10_000))
        assertEquals(Mood.WORRIED, Mascots.moodOf(7500, 10_000))
        assertEquals(Mood.WORRIED, Mascots.moodOf(8990, 10_000))
        assertEquals(Mood.UPSET, Mascots.moodOf(9000, 10_000))
        assertEquals(Mood.UPSET, Mascots.moodOf(9990, 10_000))
        assertEquals(Mood.UNWELL, Mascots.moodOf(10_000, 10_000))
        assertEquals(Mood.UNWELL, Mascots.moodOf(15_000, 10_000))
        assertEquals(Mood.CONTENT, Mascots.moodOf(5_000, null))
        assertEquals(Mood.CONTENT, Mascots.moodOf(5_000, 0))
        assertEquals(Mood.CONTENT, Mascots.moodOf(null, 10_000))
    }

    @Test
    fun animalEnumHasTen() = assertEquals(10, Animal.entries.size)
}
