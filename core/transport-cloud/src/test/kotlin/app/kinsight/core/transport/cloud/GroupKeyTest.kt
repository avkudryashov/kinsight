package app.kinsight.core.transport.cloud

import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupKeyTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val dadKeys = DeviceKeys.generate()
    private val dad = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0)
    private val kidKeys = DeviceKeys.generate()
    private val base = listOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad"),
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
    )

    @Test
    fun removalNeedsANewKeyThatTheRemovedDeviceCannotOpen() {
        val removed = base + mom.removeMemberEntry(dadKeys.deviceId, lastSeq = 0)
        val before = Membership.compute(genesis.groupId, removed)!!
        assertEquals(0, GroupKey.currentEpoch(before))
        assertTrue(GroupKey.rotationNeeded(before))
        val key = ByteArray(32) { 5 }
        val after = Membership.compute(genesis.groupId, removed + GroupKey.rotate(mom, before, key))!!
        assertEquals(1, GroupKey.currentEpoch(after))
        assertFalse(GroupKey.rotationNeeded(after))
        assertContentEquals(key, GroupKey.open(kidKeys, after)!!.second)
        assertEquals(1, GroupKey.open(momKeys, after)!!.first)
        assertNull(GroupKey.open(dadKeys, after))
    }

    @Test
    fun noDepartureNoRotationNeeded() {
        assertFalse(GroupKey.rotationNeeded(Membership.compute(genesis.groupId, base)!!))
    }

    @Test
    fun rotationByANonAdminIsVoid() {
        val state = Membership.compute(genesis.groupId, base)!!
        val after = Membership.compute(genesis.groupId, base + GroupKey.rotate(dad, state))!!
        assertEquals(0, GroupKey.currentEpoch(after))
    }

    @Test
    fun derivedKeysDifferFromTheGroupKeyAndEachOther() {
        val k = ByteArray(32) { 1 }
        assertFalse(GroupKey.packKey(k).contentEquals(k))
        assertFalse(GroupKey.packKey(k).contentEquals(GroupKey.accessKey(k)))
    }

    @Test
    fun ringKeepsWhatItIsToldTo() = runTest {
        val ring = InMemoryKeyRing(mapOf(0 to ByteArray(32)))
        ring.put(1, ByteArray(32) { 1 })
        ring.retain(setOf(1))
        assertEquals(setOf(1), ring.keys().keys)
    }

    @Test
    fun aMemberAddedBeforeTheRotationWasSeenHasNoKey() {
        val grant = mom.grantAdminEntry(dadKeys.deviceId)
        val withGrant = base + grant
        // Dad adds Grandpa having seen the grant but not Mom's rotation, which carries a higher Lamport value.
        val dadAfterGrant = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0, lastLamport = grant.lamport)
        val grandpaKeys = DeviceKeys.generate()
        val addGrandpa = dadAfterGrant.addMember(grandpaKeys.sign.publicKey, grandpaKeys.dh.publicKey, "Grandpa")
        val momLater = EntryAuthor(genesis.groupId, momKeys, lastSeq = mom.lastSeq, lastLamport = addGrandpa.lamport + 5)
        val rotation = GroupKey.rotate(momLater, Membership.compute(genesis.groupId, withGrant)!!)
        val state = Membership.compute(genesis.groupId, withGrant + addGrandpa + rotation)!!
        assertEquals(setOf(grandpaKeys.deviceId), GroupKey.membersWithoutKey(state))
        // A member added after the rotation got the newest generation in its welcome.
        val newKid = DeviceKeys.generate()
        val later = Membership.compute(genesis.groupId, withGrant + addGrandpa + rotation + momLater.addMember(newKid.sign.publicKey, newKid.dh.publicKey, "Kid2", DeviceRole.CHILD))!!
        assertEquals(setOf(grandpaKeys.deviceId), GroupKey.membersWithoutKey(later))
    }

    @Test
    fun withoutARotationNobodyLacksTheKey() {
        assertTrue(GroupKey.membersWithoutKey(Membership.compute(genesis.groupId, base)!!).isEmpty())
    }
}
