package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageHandoverEntry
import app.kinsight.core.model.storageOwnerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerLifecycleTest {
    private val momKeys = DeviceKeys.generate()
    private val dadKeys = DeviceKeys.generate()
    private val kidKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val gid = genesis.groupId
    private val mom = EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val base: List<DecodedEntry> = listOf(
        genesis.entry,
        mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
        mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD),
        mom.grantAdminEntry(dadKeys.deviceId),
    )
    private val s1 = ByteArray(16) { 1 }
    private val s2 = ByteArray(16) { 2 }
    private val mom1 = mom.storageOwnerEntry(momKeys.deviceId, s1, 1) // Lamport 5, after the four base ops

    /** An author of [keys] whose next entry has Lamport [lamport] (and seq [seq]). */
    private fun at(keys: DeviceKeys, lamport: Long, seq: Long = 100) = EntryAuthor(gid, keys, lastSeq = seq - 1, lastLamport = lamport - 1)

    private fun state(vararg more: DecodedEntry): MembershipState = Membership.compute(gid, base + mom1 + more)!!

    private fun beat(keys: DeviceKeys, generation: Long, seq: Long = 200) =
        at(keys, 1_000, seq).create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 3, storage_generation = generation)) }

    @Test
    fun aDepartureAfterTheOwnerRecordExposesTheTokenUntilTheRenewal() {
        val kidLeaves = at(kidKeys, 50).leaveGroupEntry()
        assertTrue(OwnerLifecycle.tokenExposed(state(kidLeaves)))
        val renewal = at(momKeys, 60).storageOwnerEntry(momKeys.deviceId, s1, 2)
        assertFalse(OwnerLifecycle.tokenExposed(state(kidLeaves, renewal)))
    }

    @Test
    fun aDepartureBeforeTheOwnerRecordExposesNothing() {
        val kidLeaves = at(kidKeys, 4).leaveGroupEntry() // replays after the kid's AddMember (3), before mom1 (5)
        val m = Membership.compute(gid, base + kidLeaves + mom1)!!
        assertFalse(OwnerLifecycle.tokenExposed(m))
    }

    @Test
    fun aRemovalAfterTheOwnerRecordExposesTheToken() {
        val dadRemovesKid = at(dadKeys, 40).removeMemberEntry(kidKeys.deviceId, 0)
        assertTrue(OwnerLifecycle.tokenExposed(state(dadRemovesKid)))
    }

    @Test
    fun aPhoneThatHandedItsRoleByQrAndLeftExposesNothing() {
        val dadTakesSameDisk = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s1, 2)
        val momLeaves = at(momKeys, 40, seq = 50).leaveGroupEntry()
        assertFalse(OwnerLifecycle.tokenExposed(state(dadTakesSameDisk, momLeaves)), "spec §7.3: 0 taps")
        val dadMovesToHisDisk = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s2, 2)
        assertTrue(OwnerLifecycle.tokenExposed(state(dadMovesToHisDisk, momLeaves)), "spec §7.1 then §7.4: Mom knew Dad's token")
    }

    @Test
    fun onlyTheOwnersRequestForTheCurrentGenerationNamesAParent() {
        val m = state()
        assertEquals(dadKeys.deviceId, OwnerLifecycle.handoverTo(m, listOf(at(momKeys, 20).storageHandoverEntry(dadKeys.deviceId, 1))))
        assertNull(OwnerLifecycle.handoverTo(m, listOf(at(momKeys, 20).storageHandoverEntry(kidKeys.deviceId, 1))), "never a child")
        assertNull(OwnerLifecycle.handoverTo(m, listOf(at(momKeys, 20).storageHandoverEntry(dadKeys.deviceId, 2))), "another generation")
        assertNull(OwnerLifecycle.handoverTo(m, listOf(at(dadKeys, 20).storageHandoverEntry(dadKeys.deviceId, 1))), "not from the owner")
    }

    @Test
    fun theOwnerIsUnavailableWhenGoneOrSilentForAWeek() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), now - 6 * day, now))
        assertTrue(OwnerLifecycle.ownerUnavailable(state(), now - 8 * day, now))
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), null, now), "never heard of: no claim")
        val removed = at(dadKeys, 40).removeMemberEntry(momKeys.deviceId, mom1.seq) // keeps her ops up to mom1
        assertTrue(OwnerLifecycle.ownerUnavailable(state(removed), now, now))
    }

    @Test
    fun onlyAnAdminParentOtherThanTheOwnerTakesOver() {
        val asked = listOf(at(momKeys, 20).storageHandoverEntry(dadKeys.deviceId, 1))
        val m = state()
        assertTrue(OwnerLifecycle.canTakeOver(m, dadKeys.deviceId, asked, null, 0))
        assertFalse(OwnerLifecycle.canTakeOver(m, dadKeys.deviceId, emptyList(), null, 0))
        assertFalse(OwnerLifecycle.canTakeOver(m, kidKeys.deviceId, asked, null, 0))
        assertFalse(OwnerLifecycle.canTakeOver(m, momKeys.deviceId, asked, null, 0))
    }

    @Test
    fun lastHeardIsTheOwnersNewestEntry() {
        val a = at(momKeys, 20).create { it.copy(heartbeat = Heartbeat(app_version = "t"), created_at_ms = 5) }
        val b = at(momKeys, 21, seq = 101).create { it.copy(heartbeat = Heartbeat(app_version = "t"), created_at_ms = 9) }
        assertEquals(9L, OwnerLifecycle.lastHeard(momKeys.deviceId, listOf(a, b, beat(dadKeys, 1))))
        assertNull(OwnerLifecycle.lastHeard(kidKeys.deviceId, listOf(a)))
    }

    @Test
    fun theOwnerMayNotLeaveAnotherParentWithoutTheStorage() {
        assertEquals(OwnerLeaveBlock.HANDOVER_NEEDED, OwnerLifecycle.leaveBlock(state(), momKeys.deviceId, emptyList()))
        assertNull(OwnerLifecycle.leaveBlock(state(), dadKeys.deviceId, emptyList()))
        val alone = Membership.compute(gid, listOf(genesis.entry, EntryAuthor(gid, momKeys, lastSeq = 1, lastLamport = 1).storageOwnerEntry(momKeys.deviceId, s1, 1)))!!
        assertNull(OwnerLifecycle.leaveBlock(alone, momKeys.deviceId, emptyList()), "alone: the cloud just turns off")
    }

    @Test
    fun thePreviousOwnerLeavesOnceEveryPhoneSwitchedToTheNewDisk() {
        val m = state(at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s2, 2))
        assertEquals(s1.toList(), OwnerLifecycle.previousOwner(m)!!.storageId.toByteArray().toList())
        assertEquals(OwnerLeaveBlock.SWITCHING, OwnerLifecycle.leaveBlock(m, momKeys.deviceId, listOf(beat(dadKeys, 2))))
        assertFalse(OwnerLifecycle.allSwitched(m, listOf(beat(dadKeys, 2), beat(kidKeys, 1)), 1, momKeys.deviceId))
        val beats = listOf(beat(dadKeys, 2), beat(kidKeys, 1, seq = 200), beat(kidKeys, 2, seq = 201))
        assertTrue(OwnerLifecycle.allSwitched(m, beats, 1, momKeys.deviceId), "the newest Heartbeat of each counts")
        assertNull(OwnerLifecycle.leaveBlock(m, momKeys.deviceId, beats))
        val sameDisk = state(at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s1, 2))
        assertNull(OwnerLifecycle.leaveBlock(sameDisk, momKeys.deviceId, emptyList()), "§7.3: the same Disk, nothing to wait for")
    }
}
