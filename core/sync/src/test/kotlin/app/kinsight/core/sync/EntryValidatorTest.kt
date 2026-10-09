package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EntryValidatorTest {
    private val adminKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(adminKeys, "admin")
    private val admin = EntryAuthor(genesis.groupId, adminKeys, lastSeq = 1)
    private val bobKeys = DeviceKeys.generate()
    private val bob = EntryAuthor(genesis.groupId, bobKeys, lastSeq = 0)
    private val addBob = admin.create {
        it.copy(membership_op = MembershipOp(add = AddMember(bobKeys.sign.publicKey.toByteString(), bobKeys.dh.publicKey.toByteString(), "bob")))
    }

    private fun validator(extra: List<DecodedEntry> = emptyList(), showOthers: Boolean = true): EntryValidator {
        val state = Membership.compute(genesis.groupId, listOf(genesis.entry, addBob) + extra)!!
        return EntryValidator(genesis.groupId, state, SyncPolicy(adminKeys.deviceId, showOthers, today = { TODAY }))
    }

    @Test
    fun acceptsMemberEntry() {
        assertEquals(Verdict.Accept, validator().check(bob.summary("2026-10-09", 1), 0))
    }

    @Test
    fun duplicateWhenSeqKnown() {
        val e = bob.summary("2026-10-09", 1)
        assertEquals(Verdict.Duplicate, validator().check(e, e.seq))
    }

    @Test
    fun unknownAuthorIsInvalid() {
        val stranger = EntryAuthor(genesis.groupId, DeviceKeys.generate(), 0)
        assertIs<Verdict.Invalid>(validator().check(stranger.summary("2026-10-09", 1), 0))
    }

    @Test
    fun badSignatureIsInvalid() {
        val e = bob.summary("2026-10-09", 1)
        val bad = DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertIs<Verdict.Invalid>(validator().check(bad, 0))
    }

    @Test
    fun foreignGroupIsInvalid() {
        val other = GroupGenesis.create(bobKeys, "x")
        val e = EntryAuthor(other.groupId, bobKeys, 1).summary("2026-10-09", 1)
        assertIs<Verdict.Invalid>(validator().check(e, 0))
    }

    @Test
    fun rejectsEntryAfterRemovalCutoff() {
        val e1 = bob.summary("2026-10-08", 1) // seq 1
        val e2 = bob.summary("2026-10-09", 1) // seq 2
        val remove = admin.create {
            it.copy(membership_op = MembershipOp(remove = RemoveMember(bobKeys.deviceId.bytes().toByteString(), last_seq = 1)))
        }
        val v = validator(listOf(remove))
        assertEquals(Verdict.Accept, v.check(e1, 0))
        assertIs<Verdict.Invalid>(v.check(e2, 1))
    }

    @Test
    fun expiredDetail() {
        assertEquals(Verdict.Expired, validator().check(bob.detail("2026-01-01"), 0))
    }

    @Test
    fun foreignEntryUnwantedWhenShowOthersOff() {
        assertEquals(Verdict.Unwanted, validator(showOthers = false).check(bob.summary("2026-10-09", 1), 0))
    }
}
