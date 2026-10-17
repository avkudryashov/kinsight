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
    private val t1 = ByteArray(16) { 11 }
    private val t2 = ByteArray(16) { 12 }
    private val mom1 = mom.storageOwnerEntry(momKeys.deviceId, s1, 1, t1) // Lamport 5, after the four base ops

    /** An author of [keys] whose next entry has Lamport [lamport] (and seq [seq]). */
    private fun at(keys: DeviceKeys, lamport: Long, seq: Long = 100) = EntryAuthor(gid, keys, lastSeq = seq - 1, lastLamport = lamport - 1)

    private fun state(vararg more: DecodedEntry): MembershipState = Membership.compute(gid, base + mom1 + more)!!

    private fun beat(keys: DeviceKeys, generation: Long, seq: Long = 200, protocol: Int = 3) =
        at(keys, 1_000, seq).create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = protocol, storage_generation = generation)) }

    @Test
    fun aDepartureAfterTheOwnerRecordExposesTheTokenUntilTheRenewal() {
        val kidLeaves = at(kidKeys, 50).leaveGroupEntry()
        assertTrue(OwnerLifecycle.tokenExposed(state(kidLeaves)))
        val renewal = at(momKeys, 60).storageOwnerEntry(momKeys.deviceId, s1, 2, t2, listOf(kidKeys.deviceId))
        assertFalse(OwnerLifecycle.tokenExposed(state(kidLeaves, renewal)))
    }

    @Test
    fun aDepartureTheSignerKnewOfExposesNothing() {
        val kidLeaves = at(kidKeys, 4).leaveGroupEntry()
        val owner = at(momKeys, 20, seq = 5).storageOwnerEntry(momKeys.deviceId, s1, 1, t1, listOf(kidKeys.deviceId))
        assertFalse(OwnerLifecycle.tokenExposed(Membership.compute(gid, base + kidLeaves + owner)!!))
    }

    @Test
    fun aLeaverWithALowLamportIsStillExposed() {
        val kidLeaves = at(kidKeys, 4).leaveGroupEntry() // replays after the kid's AddMember (3), before mom1 (5)
        assertTrue(OwnerLifecycle.tokenExposed(Membership.compute(gid, base + kidLeaves + mom1)!!), "the order is not evidence (Q3)")
    }

    @Test
    fun aConcurrentRemovalReplayingBeforeTheOwnerRecordIsStillExposed() {
        val dadRemovesKid = at(dadKeys, 10).removeMemberEntry(kidKeys.deviceId, 0)
        val owner = at(momKeys, 20, seq = 5).storageOwnerEntry(momKeys.deviceId, s1, 1, t1)
        val m = Membership.compute(gid, base + dadRemovesKid + owner)!!
        assertTrue(kidKeys.deviceId in m.departed)
        assertTrue(OwnerLifecycle.tokenExposed(m), "Mom did not know of the removal when she handed the token out")
    }

    @Test
    fun aRemovalAfterTheOwnerRecordExposesTheToken() {
        val dadRemovesKid = at(dadKeys, 40).removeMemberEntry(kidKeys.deviceId, 0)
        assertTrue(OwnerLifecycle.tokenExposed(state(dadRemovesKid)))
    }

    @Test
    fun aTransferKeepingTheTokenKeepsAnEarlierExposure() {
        val kidLeaves = at(kidKeys, 50).leaveGroupEntry()
        val sameToken = at(dadKeys, 60).storageOwnerEntry(dadKeys.deviceId, s1, 2, t1)
        assertTrue(OwnerLifecycle.tokenExposed(state(kidLeaves, sameToken)), "review I2: the same token goes on")
        val claimsToKnow = at(dadKeys, 60).storageOwnerEntry(dadKeys.deviceId, s1, 2, t1, listOf(kidKeys.deviceId))
        assertTrue(OwnerLifecycle.tokenExposed(state(kidLeaves, claimsToKnow)), "the op that issued the token decides")
    }

    @Test
    fun aPhoneThatHandedItsRoleByQrAndLeftExposesNothing() {
        val dadTakesSameToken = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s1, 2, t1)
        val momLeaves = at(momKeys, 40, seq = 50).leaveGroupEntry()
        // The transfer invite: Mom makes Dad admin again after her StorageOwner op (as Pairing does for the new phone).
        val momHandsOver = at(momKeys, 25, seq = 6).grantAdminEntry(dadKeys.deviceId)
        assertFalse(OwnerLifecycle.tokenExposed(state(momHandsOver, dadTakesSameToken, momLeaves)), "spec §7.3: 0 taps")
        val dadRemovesMom = at(dadKeys, 40).removeMemberEntry(momKeys.deviceId, momHandsOver.seq)
        assertTrue(OwnerLifecycle.tokenExposed(state(momHandsOver, dadTakesSameToken, dadRemovesMom)), "Q6: a removal renews (spec §7.4)")
        val dadSignsInOnTheSameDisk = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s1, 2, t2)
        assertTrue(OwnerLifecycle.tokenExposed(state(momHandsOver, dadSignsInOnTheSameDisk, momLeaves)), "Mom could read Dad's new token")
        val dadMovesToHisDisk = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s2, 2, t2)
        assertTrue(OwnerLifecycle.tokenExposed(state(momHandsOver, dadMovesToHisDisk, momLeaves)), "spec §7.1 then §7.4: Mom knew Dad's token")
    }

    /** Y3 parked minor: an AddMember or GrantAdmin by the leaver from before its own StorageOwner op is no transfer. */
    @Test
    fun theQrExceptionNeedsTheHandOverAfterTheLeaversOwnerRecord() {
        val dadTakesSameToken = at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s1, 2, t1)
        val momLeaves = at(momKeys, 40, seq = 50).leaveGroupEntry()
        // Mom added Dad and made him admin in the base, before mom1: nothing says she handed him the storage.
        assertTrue(OwnerLifecycle.tokenExposed(state(dadTakesSameToken, momLeaves)))
        val phoneKeys = DeviceKeys.generate()
        val momAddsPhone = at(momKeys, 25, seq = 6).addMember(phoneKeys.sign.publicKey, phoneKeys.dh.publicKey, "Phone", DeviceRole.PARENT)
        val momGrantsPhone = at(momKeys, 26, seq = 7).grantAdminEntry(phoneKeys.deviceId)
        val phoneTakes = at(phoneKeys, 30, seq = 1).storageOwnerEntry(phoneKeys.deviceId, s1, 2, t1)
        assertFalse(OwnerLifecycle.tokenExposed(state(momAddsPhone, momGrantsPhone, phoneTakes, momLeaves)), "the QR transfer invite")
    }

    @Test
    fun theQrExceptionNeedsTheTransferSignedByThePreviousOwner() {
        // Dad, not Mom, added the new phone: nothing says Mom handed her role to it.
        val phoneKeys = DeviceKeys.generate()
        val dad = at(dadKeys, 20)
        val addPhone = dad.addMember(phoneKeys.sign.publicKey, phoneKeys.dh.publicKey, "Phone", DeviceRole.PARENT)
        val grant = dad.grantAdminEntry(phoneKeys.deviceId)
        val phoneTakes = at(phoneKeys, 30, seq = 1).storageOwnerEntry(phoneKeys.deviceId, s1, 2, t1)
        val momLeaves = at(momKeys, 40, seq = 50).leaveGroupEntry()
        val m = state(addPhone, grant, phoneTakes, momLeaves)
        assertEquals(phoneKeys.deviceId, m.storageOwner!!.deviceId)
        assertTrue(OwnerLifecycle.tokenExposed(m))
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
    fun theOwnerIsUnavailableWhenGoneOrNotHeardHereForAWeek() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), now - 6 * day, now))
        assertTrue(OwnerLifecycle.ownerUnavailable(state(), now - 7 * day, now), "a full week on this device's clock")
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), null, now), "never tracked here: no claim")
        val removed = at(dadKeys, 40).removeMemberEntry(momKeys.deviceId, mom1.seq) // keeps her ops up to mom1
        assertTrue(OwnerLifecycle.ownerUnavailable(state(removed), now, now))
    }

    @Test
    fun aNewOwnerSeqIsHeardOnTheLocalClockAndNothingElseIs() {
        val first = OwnerLifecycle.ownerHeard(null, momKeys.deviceId, newestSeq = 5, nowMs = 1_000)
        assertEquals(OwnerHeard(momKeys.deviceId, 5, 1_000), first, "first tracked: the week starts now")
        assertEquals(first, OwnerLifecycle.ownerHeard(first, momKeys.deviceId, 5, 2_000), "nothing new")
        assertEquals(first, OwnerLifecycle.ownerHeard(first, momKeys.deviceId, 4, 2_000), "never backwards")
        assertEquals(OwnerHeard(momKeys.deviceId, 6, 3_000), OwnerLifecycle.ownerHeard(first, momKeys.deviceId, 6, 3_000))
        assertEquals(OwnerHeard(dadKeys.deviceId, 2, 4_000), OwnerLifecycle.ownerHeard(first, dadKeys.deviceId, 2, 4_000), "another owner")
    }

    /** Rulings Q14: the owner's own clock never decides; only when this device saw its log grow. */
    @Test
    fun aFutureOwnerStampDecidesNothing() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val heard = OwnerLifecycle.ownerHeard(null, momKeys.deviceId, newestSeq = 5, nowMs = now)
        // The owner's next entry is stamped a month ahead; it is still heard at the reader's now.
        val ahead = at(momKeys, 20, seq = 6).create { it.copy(heartbeat = Heartbeat(app_version = "t"), created_at_ms = now + 30 * day) }
        val later = OwnerLifecycle.ownerHeard(heard, momKeys.deviceId, ahead.seq, now + day)
        assertEquals(now + day, later.heardAtMs)
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), later.heardAtMs, now + 7 * day))
        assertTrue(OwnerLifecycle.ownerUnavailable(state(), later.heardAtMs, now + 8 * day), "silent a week here, whatever its stamp")
    }

    @Test
    fun aLaggingOwnerClockDecidesNothing() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val heard = OwnerLifecycle.ownerHeard(null, momKeys.deviceId, newestSeq = 5, nowMs = now - day)
        // The owner's clock runs three weeks behind: its fresh entry would look silent by its stamp, but it is new here.
        val behind = at(momKeys, 20, seq = 6).create { it.copy(heartbeat = Heartbeat(app_version = "t"), created_at_ms = now - 21 * day) }
        val later = OwnerLifecycle.ownerHeard(heard, momKeys.deviceId, behind.seq, now)
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), later.heardAtMs, now))
        assertFalse(OwnerLifecycle.ownerUnavailable(state(), later.heardAtMs, now + 6 * day))
        assertTrue(OwnerLifecycle.ownerUnavailable(state(), later.heardAtMs, now + 7 * day))
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

    /** Spec §7.1 «есть другой родитель»: a parent who is not an admin yet blocks the leave too (the handover makes one). */
    @Test
    fun aParentWhoIsNotAnAdminAlsoWaitsForTheStorage() {
        val m = Membership.compute(gid, base.filter { it.body.membership_op?.set_admin == null } + mom1)!!
        assertFalse(m.active.getValue(dadKeys.deviceId).isAdmin)
        assertEquals(OwnerLeaveBlock.HANDOVER_NEEDED, OwnerLifecycle.leaveBlock(m, momKeys.deviceId, emptyList()))
        val kidOnly = Membership.compute(gid, listOf(base[0], base[2]) + mom1)!!
        assertNull(OwnerLifecycle.leaveBlock(kidOnly, momKeys.deviceId, emptyList()), "a child never takes the storage")
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

    /** Final review, Important 1: a build older than the cloud (protocol 2) never holds a token, so it is not waited for. */
    @Test
    fun aMemberOnAnOldBuildDoesNotHoldUpTheSwitch() {
        val m = state(at(dadKeys, 30).storageOwnerEntry(dadKeys.deviceId, s2, 2))
        val old = listOf(beat(dadKeys, 2), beat(kidKeys, 0, protocol = 2))
        assertTrue(OwnerLifecycle.allSwitched(m, old, 1, momKeys.deviceId))
        assertNull(OwnerLifecycle.leaveBlock(m, momKeys.deviceId, old), "the former owner may leave")
        val updated = listOf(beat(dadKeys, 2), beat(kidKeys, 0, seq = 200, protocol = 2), beat(kidKeys, 1, seq = 201))
        assertFalse(OwnerLifecycle.allSwitched(m, updated, 1, momKeys.deviceId), "once updated, its newest Heartbeat counts")
        assertFalse(OwnerLifecycle.allSwitched(m, listOf(beat(dadKeys, 2)), 1, momKeys.deviceId), "no Heartbeat yet: still waited for")
    }
}
