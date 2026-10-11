package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.CategoryOverride
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import app.kinsight.core.model.proto.RotateDiscoveryKey
import app.kinsight.core.model.proto.SealedKey
import app.kinsight.core.model.proto.UsageSummary
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertEquals(Verdict.Accept, validator().check(bob.summary("2026-10-09", 1), alreadyAccounted = false))
    }

    @Test
    fun duplicateWhenSeqAccounted() {
        val e = bob.summary("2026-10-09", 1)
        assertEquals(Verdict.Duplicate, validator().check(e, alreadyAccounted = true))
    }

    @Test
    fun unknownAuthorIsInvalid() {
        val stranger = EntryAuthor(genesis.groupId, DeviceKeys.generate(), 0)
        assertIs<Verdict.Invalid>(validator().check(stranger.summary("2026-10-09", 1), alreadyAccounted = false))
    }

    @Test
    fun badSignatureIsInvalid() {
        val e = bob.summary("2026-10-09", 1)
        val bad = DecodedEntry.decode(e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        assertIs<Verdict.Invalid>(validator().check(bad, alreadyAccounted = false))
    }

    @Test
    fun foreignGroupIsInvalid() {
        val other = GroupGenesis.create(bobKeys, "x")
        val e = EntryAuthor(other.groupId, bobKeys, 1).summary("2026-10-09", 1)
        assertIs<Verdict.Invalid>(validator().check(e, alreadyAccounted = false))
    }

    @Test
    fun rejectsEntryAfterRemovalCutoff() {
        val e1 = bob.summary("2026-10-08", 1) // seq 1
        val e2 = bob.summary("2026-10-09", 1) // seq 2
        val remove = admin.create {
            it.copy(membership_op = MembershipOp(remove = RemoveMember(bobKeys.deviceId.bytes().toByteString(), last_seq = 1)))
        }
        val v = validator(listOf(remove))
        assertEquals(Verdict.Accept, v.check(e1, alreadyAccounted = false))
        assertIs<Verdict.Invalid>(v.check(e2, alreadyAccounted = false))
    }

    private fun addNamed(name: String) = admin.create {
        val k = DeviceKeys.generate()
        it.copy(membership_op = MembershipOp(add = AddMember(k.sign.publicKey.toByteString(), k.dh.publicKey.toByteString(), name)))
    }

    @Test
    fun displayNameCappedAt64CodePoints() {
        assertEquals(Verdict.Accept, validator().check(addNamed("Планшет бабушки Валентины Петровны"), alreadyAccounted = false)) // 65 bytes
        assertEquals(Verdict.Accept, validator().check(addNamed("é".repeat(64)), alreadyAccounted = false)) // 128 bytes
        assertIs<Verdict.Invalid>(validator().check(addNamed("x".repeat(65)), alreadyAccounted = false))
    }

    @Test
    fun lamportAtOrAbove2Pow62IsInvalid() {
        fun withLamport(l: Long) = bob.create { it.copy(usage_summary = UsageSummary(local_date = "2026-10-09"), lamport = l) }
        assertEquals(Verdict.Accept, validator().check(withLamport((1L shl 62) - 1), alreadyAccounted = false))
        assertIs<Verdict.Invalid>(validator().check(withLamport(1L shl 62), alreadyAccounted = false))
        assertIs<Verdict.Invalid>(validator().check(withLamport(-1L), alreadyAccounted = false)) // uint64 2^64-1
    }

    @Test
    fun membershipEntryCappedAt8KiB() {
        fun rotate(size: Int) = admin.create {
            it.copy(membership_op = MembershipOp(rotate = RotateDiscoveryKey(epoch = 1, keys = listOf(SealedKey(ciphertext = ByteArray(size).toByteString())))))
        }
        assertEquals(Verdict.Accept, validator().check(rotate(7_000), alreadyAccounted = false))
        val big = rotate(8_200)
        assertTrue(big.bytes.size > 8 * 1024)
        assertIs<Verdict.Invalid>(validator().check(big, alreadyAccounted = false))
        // Non-membership entries are not subject to the cap.
        val bigSummary = bob.create { it.copy(usage_summary = UsageSummary(local_date = "2026-10-09", zone_id = "x".repeat(9_000))) }
        assertEquals(Verdict.Accept, validator().check(bigSummary, alreadyAccounted = false))
    }

    @Test
    fun expiredDetail() {
        assertEquals(Verdict.Expired, validator().check(bob.detail("2026-01-01"), alreadyAccounted = false))
    }

    @Test
    fun foreignEntryUnwantedWhenShowOthersOff() {
        assertEquals(Verdict.Unwanted, validator(showOthers = false).check(bob.summary("2026-10-09", 1), alreadyAccounted = false))
    }

    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val addKid = admin.create {
        it.copy(
            membership_op = MembershipOp(
                add = AddMember(kidKeys.sign.publicKey.toByteString(), kidKeys.dh.publicKey.toByteString(), "kid", DeviceRole.CHILD),
            ),
        )
    }

    private fun overrideOf(author: EntryAuthor, pkg: String = "com.example.game", category: Int = 1) =
        author.create { it.copy(category_override = CategoryOverride(package_name = pkg, category = category)) }

    @Test
    fun categoryOverrideFromParentsIsAccepted() {
        val v = validator(listOf(addKid))
        assertEquals(Verdict.Accept, v.check(overrideOf(admin), alreadyAccounted = false))
        // A member added without a role reads as PARENT (spec §4.8).
        assertEquals(Verdict.Accept, v.check(overrideOf(bob), alreadyAccounted = false))
    }

    @Test
    fun categoryOverrideFromChildIsInvalid() {
        assertIs<Verdict.Invalid>(validator(listOf(addKid)).check(overrideOf(kid), alreadyAccounted = false))
    }

    @Test
    fun categoryOverrideFromNonMemberIsInvalid() {
        val stranger = EntryAuthor(genesis.groupId, DeviceKeys.generate(), 0)
        assertIs<Verdict.Invalid>(validator(listOf(addKid)).check(overrideOf(stranger), alreadyAccounted = false))
    }

    @Test
    fun categoryOverrideOfADepartedParentWithinItsCutoffIsKeptAndAfterItIsInvalid() {
        val before = overrideOf(bob) // seq 1
        val after = overrideOf(bob, pkg = "com.example.other") // seq 2
        val remove = admin.create {
            it.copy(membership_op = MembershipOp(remove = RemoveMember(bobKeys.deviceId.bytes().toByteString(), last_seq = 1)))
        }
        val v = validator(listOf(remove))
        // Written while bob was a parent: still valid on every replica (validity never flips with later membership
        // changes, so relays cannot trip TOO_MANY_INVALID); the read model ignores departed parents (spec §4.4).
        assertEquals(Verdict.Accept, v.check(before, alreadyAccounted = false))
        assertIs<Verdict.Invalid>(v.check(after, alreadyAccounted = false))
    }

    @Test
    fun categoryOverridePackageRules() {
        val v = validator()
        assertEquals(Verdict.Accept, v.check(overrideOf(bob, pkg = "a".repeat(255)), alreadyAccounted = false))
        for (bad in listOf("", "~other", "a/b", "a|b", "a b", "a\tb", "a\nb", "a".repeat(256), "é".repeat(128))) {
            assertIs<Verdict.Invalid>(v.check(overrideOf(bob, pkg = bad), alreadyAccounted = false), "package '$bad'")
        }
    }

    @Test
    fun categoryOverrideCategoryRange() {
        val v = validator()
        for (ok in 0..CategoryOverrideRules.MAX_CATEGORY) {
            assertEquals(Verdict.Accept, v.check(overrideOf(bob, category = ok), alreadyAccounted = false), "category $ok")
        }
        assertEquals(9, CategoryOverrideRules.MAX_CATEGORY)
        for (bad in listOf(-1, 10, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertIs<Verdict.Invalid>(v.check(overrideOf(bob, category = bad), alreadyAccounted = false), "category $bad")
        }
    }

    @Test
    fun categoryOverrideBodyCappedAt1KiB() {
        // Padding as an unknown field inside CategoryOverride (a future field an old reader keeps verbatim).
        fun padded(n: Int) = bob.create {
            val pad = Buffer().writeByte(15 shl 3 or 2).also { b ->
                var len = n
                while (len >= 0x80) { b.writeByte(len and 0x7f or 0x80); len = len ushr 7 }
                b.writeByte(len)
            }.write(ByteArray(n)).readByteString()
            it.copy(category_override = CategoryOverride(package_name = "com.example.game", category = 1, unknownFields = pad))
        }
        val v = validator()
        val fits = (800 downTo 0).map(::padded).first { it.bodySize <= 1024 }
        assertEquals(Verdict.Accept, v.check(fits, alreadyAccounted = false))
        val big = padded(1_000)
        assertTrue(big.bodySize > 1024)
        assertIs<Verdict.Invalid>(v.check(big, alreadyAccounted = false))
    }

    @Test
    fun categoryOverrideUnwantedOnAChild() {
        assertEquals(Verdict.Unwanted, validator(showOthers = false).check(overrideOf(bob), alreadyAccounted = false))
    }

    @Test
    fun categoryOverrideNeverExpires() {
        assertTrue(!SyncPolicy(adminKeys.deviceId, today = { TODAY.plusYears(50) }).isExpired(overrideOf(bob)))
    }
}
