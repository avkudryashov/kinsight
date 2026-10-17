package app.kinsight.core.transport.cloud

import kotlinx.coroutines.test.runTest
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.sync.summary
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CloudAccessViewTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val dadKeys = DeviceKeys.generate()
    private val dad = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0)
    private val kidKeys = DeviceKeys.generate()
    private val gen0 = Primitives.randomBytes(32)
    private val storage = ByteArray(16) { 3 }
    private val base = listOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad"),
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
    )
    private val owner = mom.storageOwnerEntry(momKeys.deviceId, storage, 1)
    private val membership = Membership.compute(genesis.groupId, base + owner)!!

    @Test
    fun everyMemberWithTheKeyReadsTheToken() {
        val access = CloudAccessView.issue(mom, membership, 0, gen0, "AT", expiresAtMs = 99)
        val seen = CloudAccessView.current(membership, base + owner + access, mapOf(0 to gen0))!!
        assertEquals("AT", seen.accessToken)
        assertEquals(99L, seen.expiresAtMs)
        assertEquals(momKeys.deviceId, seen.owner)
        assertEquals(1L, seen.generation)
        assertFalse("AT" in seen.toString())
    }

    @Test
    fun anAccessTheRulesWouldRefuseIsNeverSigned() {
        val before = mom.lastSeq
        // A token so long the sealed record outgrows CloudAccessRules: a child would hold the owner's log open on it.
        val huge = "x".repeat(app.kinsight.core.sync.CloudAccessRules.MAX_CIPHERTEXT_BYTES)
        assertFailsWith<IllegalArgumentException> { CloudAccessView.issue(mom, membership, 0, gen0, huge, expiresAtMs = 99) }
        assertEquals(before, mom.lastSeq, "no seq spent")
        val ok = CloudAccessView.issue(mom, membership, 0, gen0, "AT", expiresAtMs = 99)
        assertNull(app.kinsight.core.sync.CloudAccessRules.violation(ok, membership))
    }

    @Test
    fun tokenNeverAppearsInClearInTheLogEntryOrToString() {
        val token = "y0_AgAAAAB-test-token-1234567890"
        val access = CloudAccessView.issue(mom, membership, 0, gen0, token, expiresAtMs = 99)
        assertFalse(contains(access.bytes, token.toByteArray()))
        val seen = CloudAccessView.current(membership, base + owner + access, mapOf(0 to gen0))!!
        assertEquals(token, seen.accessToken)
        assertFalse(token in seen.toString())
    }

    @Test
    fun accessOfAnotherParentIsNotUsed() {
        val withDadAdmin = Membership.compute(genesis.groupId, base + owner + mom.grantAdminEntry(dadKeys.deviceId))!!
        // issue() refuses a non-owner; Dad's entry is valid on the wire but is not the owner's.
        val dadAccess = dad.create {
            it.copy(cloud_access = CloudAccess(generation = 1, storage_id = storage.toByteString(), nonce = ByteArray(12).toByteString(), ciphertext = ByteArray(40).toByteString()))
        }
        assertNull(CloudAccessView.current(withDadAdmin, base + owner + dadAccess, mapOf(0 to gen0)))
    }

    @Test
    fun accessForAnOldStorageGenerationIsNotUsed() {
        val access = CloudAccessView.issue(mom, membership, 0, gen0, "AT", expiresAtMs = 1)
        val moved = mom.storageOwnerEntry(momKeys.deviceId, ByteArray(16) { 4 }, 2)
        val later = Membership.compute(genesis.groupId, base + owner + moved)!!
        assertNull(CloudAccessView.current(later, base + owner + moved + access, mapOf(0 to gen0)))
    }

    @Test
    fun removedDeviceCannotReadTheNewAccess() {
        val removal = mom.removeMemberEntry(dadKeys.deviceId, lastSeq = 0)
        val afterRemoval = Membership.compute(genesis.groupId, base + owner + removal)!!
        val rotation = GroupKey.rotate(mom, afterRemoval)
        val rotated = Membership.compute(genesis.groupId, base + owner + removal + rotation)!!
        val (epoch, gen1) = GroupKey.open(momKeys, rotated)!!
        val access = CloudAccessView.issue(mom, rotated, epoch, gen1, "NEW", expiresAtMs = 1)
        val entries = base + owner + removal + rotation + access
        assertEquals("NEW", CloudAccessView.current(rotated, entries, mapOf(0 to gen0, 1 to GroupKey.open(kidKeys, rotated)!!.second))!!.accessToken)
        assertNull(CloudAccessView.current(rotated, entries, mapOf(0 to gen0))) // all Dad holds
    }

    private fun contains(hay: ByteArray, needle: ByteArray) =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    /** Re-signs [sealed]'s ciphertext with outer fields rewritten to match the current owner, so only the AAD can catch it. */
    private fun rewritten(sealed: CloudAccess) = mom.create {
        it.copy(cloud_access = sealed.copy(generation = 1, storage_id = storage.toByteString(), key_epoch = 0))
    }

    private fun sealedBy(storageId: Byte, generation: Long, epoch: Int): CloudAccess {
        val st = mom.storageOwnerEntry(momKeys.deviceId, ByteArray(16) { storageId }, generation)
        val m = Membership.compute(genesis.groupId, base + st)!!
        return CloudAccessView.issue(mom, m, epoch, gen0, "AT", expiresAtMs = 1).body.cloud_access!!
    }

    @Test
    fun recordSealedForAnotherStorageIdIsNotAccepted() {
        val forged = rewritten(sealedBy(9, 1, 0))
        assertNull(CloudAccessView.current(membership, base + owner + forged, mapOf(0 to gen0)))
    }

    @Test
    fun recordSealedForAnotherGenerationIsNotAccepted() {
        val forged = rewritten(sealedBy(3, 2, 0))
        assertNull(CloudAccessView.current(membership, base + owner + forged, mapOf(0 to gen0)))
    }

    @Test
    fun recordSealedForAnotherKeyEpochIsNotAccepted() {
        val forged = rewritten(sealedBy(3, 1, 5))
        assertNull(CloudAccessView.current(membership, base + owner + forged, mapOf(0 to gen0)))
    }

    private fun malformed(edit: (CloudAccess) -> CloudAccess): StorageAccess? {
        val good = CloudAccessView.issue(mom, membership, 0, gen0, "AT", expiresAtMs = 1).body.cloud_access!!
        val bad = mom.create { it.copy(cloud_access = edit(good)) }
        return CloudAccessView.current(membership, base + owner + bad, mapOf(0 to gen0))
    }

    @Test
    fun shortNonceGivesNull() {
        assertNull(malformed { it.copy(nonce = ByteArray(11).toByteString()) })
    }

    @Test
    fun ciphertextShorterThanTheTagGivesNull() {
        assertNull(malformed { it.copy(ciphertext = ByteArray(15).toByteString()) })
        assertNull(malformed { it.copy(ciphertext = okio.ByteString.EMPTY) })
    }

    @Test
    fun authenticCiphertextOfGarbageGivesNull() {
        val groupId = genesis.groupId.bytes()
        val aad = groupId + java.nio.ByteBuffer.allocate(12).putLong(1L).putInt(0).array() + storage
        val nonce = ByteArray(12) { 1 }
        val sealed = Primitives.chachaSeal(GroupKey.accessKey(gen0), nonce, aad, ByteArray(64) { 0xFF.toByte() })
        assertNull(malformed { it.copy(nonce = nonce.toByteString(), ciphertext = sealed.toByteString()) })
    }

    @Test
    fun currentIsReadFromTheOwnersLogOnly() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        mom.publish(mom.author.storageOwnerEntry(mom.id, ByteArray(16) { 1 }, 1))
        mom.publish(CloudAccessView.issue(mom.author, mom.membership(), 0, GEN0, "OLD", 9))
        mom.publish(CloudAccessView.issue(mom.author, mom.membership(), 0, GEN0, "NEW", 9))
        // Entries after the newest access push it off the last page.
        repeat(3) { mom.publish(mom.author.summary("2026-10-0${it + 1}", 1)) }
        // A non-owner's CloudAccess is in the store, with a higher seq than any of the owner's, and is ignored.
        mom.publish(
            dad.author.create {
                it.copy(cloud_access = CloudAccess(generation = 1, storage_id = ByteArray(16) { 1 }.toByteString(), nonce = ByteArray(12).toByteString(), ciphertext = ByteArray(40).toByteString()))
            },
        )
        for (page in listOf(2, 1, 500)) {
            assertEquals("NEW", CloudAccessView.current(mom.store, mom.membership(), mapOf(0 to GEN0), page)!!.accessToken, "page $page")
        }
        assertNull(CloudAccessView.current(kid.store, kid.membership(), mapOf(0 to GEN0)), "the kid knows no owner yet")
    }
}
