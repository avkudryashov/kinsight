package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.DeviceRole
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudAccessRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val state = Membership.compute(genesis.groupId, listOf(genesis.entry, addKid))!!

    private fun EntryAuthor.access(generation: Long = 1, storage: Int = 16, nonce: Int = 12, ct: Int = 80): DecodedEntry = create {
        it.copy(
            cloud_access = CloudAccess(
                generation = generation, storage_id = ByteArray(storage).toByteString(),
                nonce = ByteArray(nonce).toByteString(), ciphertext = ByteArray(ct).toByteString(),
            ),
        )
    }

    private fun verdict(e: DecodedEntry, self: DeviceKeys = momKeys, showOthers: Boolean = true) =
        EntryValidator(genesis.groupId, state, SyncPolicy(self.deviceId, showOthers, today = { TODAY })).check(e, alreadyAccounted = false)

    @Test
    fun parentAccessIsAccepted() = assertEquals(Verdict.Accept, verdict(mom.access()))

    @Test
    fun childAccessIsSkipped() = assertIs<Verdict.Skipped>(verdict(kid.access()))

    @Test
    fun malformedAccessIsSkipped() {
        assertIs<Verdict.Skipped>(verdict(mom.access(generation = 0)))
        assertIs<Verdict.Skipped>(verdict(mom.access(storage = 15)))
        assertIs<Verdict.Skipped>(verdict(mom.access(nonce = 11)))
        assertIs<Verdict.Skipped>(verdict(mom.access(ct = 16)))
        assertIs<Verdict.Skipped>(verdict(mom.access(ct = CloudAccessRules.MAX_CIPHERTEXT_BYTES + 1)))
    }

    @Test
    fun childTakesParentAccessDespiteShowOthersOff() {
        assertEquals(Verdict.Accept, verdict(mom.access(), self = kidKeys, showOthers = false))
        assertEquals(Verdict.Unwanted, verdict(mom.summary("2026-10-09", 1), self = kidKeys, showOthers = false))
        assertTrue(EntryKind.CLOUD_ACCESS in SyncPolicy.FROM_PARENTS_TO_ALL)
    }
}
