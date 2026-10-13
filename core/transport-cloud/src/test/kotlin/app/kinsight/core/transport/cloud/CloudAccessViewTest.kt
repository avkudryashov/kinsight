package app.kinsight.core.transport.cloud

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
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
