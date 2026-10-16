package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.DeviceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParentPinRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val state = Membership.compute(genesis.groupId, listOf(genesis.entry, addKid))!!

    /** By default as the child's phone receives it: showOthers off. */
    private fun verdict(e: DecodedEntry, self: DeviceKeys = kidKeys, showOthers: Boolean = false) =
        EntryValidator(genesis.groupId, state, SyncPolicy(self.deviceId, showOthers)).check(e, alreadyAccounted = false)

    private fun EntryAuthor.pin(salt: Int = 16, hash: Int = 32, memory: Int = 19_456, iterations: Int = 2, rank: Long = 1) =
        parentPinEntry(ByteArray(salt) { 1 }, ByteArray(hash) { 2 }, memory, iterations, rank)

    @Test
    fun aChildTakesTheParentsPinHash() {
        val e = mom.pin()
        assertEquals(EntryKind.PARENT_PIN, e.kind)
        assertEquals("${momKeys.deviceId.hex}/pin", e.supersedeKey)
        assertEquals(Verdict.Accept, verdict(e), "spec §4: the child reads the parents' settings")
        assertEquals(Verdict.Accept, verdict(e, self = momKeys, showOthers = true))
    }

    @Test
    fun aMalformedOrAChildsPinIsSkipped() {
        assertIs<Verdict.Skipped>(verdict(kid.pin(), self = momKeys, showOthers = true))
        assertIs<Verdict.Skipped>(verdict(mom.pin(salt = 15)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(hash = 31)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(memory = 8_191)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(memory = 65_537)), "a cost that would exhaust the child's phone")
        assertIs<Verdict.Skipped>(verdict(mom.pin(iterations = 0)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(iterations = 5)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(rank = ParentPinRules.MAX_RANK + 1)))
        assertIs<Verdict.Skipped>(verdict(mom.pin(rank = -1)))
    }
}
