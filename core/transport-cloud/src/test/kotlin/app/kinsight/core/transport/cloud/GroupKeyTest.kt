package app.kinsight.core.transport.cloud

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
}
