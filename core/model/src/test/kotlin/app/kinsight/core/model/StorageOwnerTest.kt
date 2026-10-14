package app.kinsight.core.model

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.StorageOwner
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageOwnerTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val dadKeys = DeviceKeys.generate()
    private val dad = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0)
    private val kidKeys = DeviceKeys.generate()
    private val addDad = mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad")
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val storage = ByteArray(16) { 1 }

    private fun state(vararg extra: DecodedEntry) = Membership.compute(genesis.groupId, listOf(genesis.entry, addDad, addKid) + extra)!!

    @Test
    fun adminNamesItselfOwner() {
        val op = mom.storageOwnerEntry(momKeys.deviceId, storage, 1)
        val owner = state(op).storageOwner!!
        assertEquals(momKeys.deviceId, owner.deviceId)
        assertEquals(1L, owner.generation)
        assertEquals(16, owner.storageId.size)
    }

    @Test
    fun nonAdminOpIsIgnored() {
        assertNull(state(dad.storageOwnerEntry(dadKeys.deviceId, storage, 1)).storageOwner)
    }

    @Test
    fun childCannotOwnAndNonAdminCannotBeNamed() {
        assertNull(state(mom.storageOwnerEntry(kidKeys.deviceId, storage, 1)).storageOwner)
        assertNull(state(mom.storageOwnerEntry(dadKeys.deviceId, storage, 1)).storageOwner) // Dad is not an admin yet
    }

    @Test
    fun higherGenerationWinsAndLowerOrEqualIsVoid() {
        val grant = mom.grantAdminEntry(dadKeys.deviceId)
        val first = mom.storageOwnerEntry(momKeys.deviceId, storage, 1)
        val moved = mom.storageOwnerEntry(dadKeys.deviceId, ByteArray(16) { 2 }, 2)
        val stale = mom.storageOwnerEntry(momKeys.deviceId, storage, 2)
        val s = state(grant, first, moved, stale)
        assertEquals(dadKeys.deviceId, s.storageOwner!!.deviceId)
        assertEquals(2L, s.storageOwner!!.generation)
        assertEquals(setOf(first, moved), s.effectiveEntries.filter { it.body.membership_op?.storage_owner != null }.toSet())
    }

    @Test
    fun badStorageIdIsVoid() {
        assertNull(state(mom.storageOwnerEntry(momKeys.deviceId, ByteArray(15), 1)).storageOwner)
    }

    @Test
    fun theTokenIdAndTheDepartedTheSignerKnewGoIntoTheState() {
        val token = ByteArray(16) { 7 }
        val owner = state(mom.storageOwnerEntry(momKeys.deviceId, storage, 1, token, listOf(kidKeys.deviceId))).storageOwner!!
        assertEquals(token.toByteString(), owner.tokenId)
        assertEquals(setOf(kidKeys.deviceId), owner.departed)
        val legacy = state(mom.storageOwnerEntry(momKeys.deviceId, storage, 1)).storageOwner!!
        assertEquals(ByteString.EMPTY, legacy.tokenId, "an op written before Y3")
        assertEquals(emptySet(), legacy.departed)
    }

    @Test
    fun malformedDepartedIdsAreDroppedAndTheListIsCapped() {
        val ids = List(MembershipLimits.MAX_OWNER_DEPARTED) { i -> ByteArray(16) { i.toByte() }.toByteString() } +
            kidKeys.deviceId.bytes().toByteString()
        val op = mom.create {
            it.copy(
                membership_op = MembershipOp(
                    storage_owner = StorageOwner(momKeys.deviceId.bytes().toByteString(), storage.toByteString(), 1, departed = listOf(ByteArray(15).toByteString()) + ids),
                ),
            )
        }
        val departed = state(op).storageOwner!!.departed
        assertEquals(MembershipLimits.MAX_OWNER_DEPARTED - 1, departed.size, "the first 256 read, the 15-byte one dropped")
        assertFailsWith<IllegalArgumentException> {
            mom.storageOwnerEntry(momKeys.deviceId, storage, 1, departed = List(MembershipLimits.MAX_OWNER_DEPARTED + 1) { i -> DeviceId.fromBytes(ByteArray(16) { i.toByte() }.also { it[0] = (i shr 8).toByte() }) })
        }
    }

    @Test
    fun theWriterListsEachDepartedIdOnceAndUpTo256() {
        assertEquals(256, MembershipLimits.MAX_OWNER_DEPARTED, "rulings Q16")
        val many = List(MembershipLimits.MAX_OWNER_DEPARTED) { i -> DeviceId.fromBytes(ByteArray(16) { i.toByte() }.also { it[0] = (i shr 8).toByte() }) }
        val full = mom.storageOwnerEntry(momKeys.deviceId, storage, 1, ByteArray(16) { 7 }, many)
        assertEquals(many.toSet(), state(full).storageOwner!!.departed, "a full list fits in one membership op")
        val repeated = mom.storageOwnerEntry(momKeys.deviceId, storage, 1, departed = List(MembershipLimits.MAX_OWNER_DEPARTED + 1) { kidKeys.deviceId })
        assertEquals(listOf(kidKeys.deviceId.bytes().toByteString()), repeated.body.membership_op!!.storage_owner!!.departed)
    }
}
