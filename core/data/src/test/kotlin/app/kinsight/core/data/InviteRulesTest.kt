package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PairReject
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.pairing.Joiner
import kotlin.test.Test
import kotlin.test.assertEquals

class InviteRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, lastLamport = genesis.entry.lamport)
    private val a = DeviceKeys.generate()
    private val b = DeviceKeys.generate()
    private fun joiner(k: DeviceKeys) = Joiner(k.sign.publicKey, k.dh.publicKey, "x")
    private val empty = Membership.compute(genesis.groupId, listOf(genesis.entry))!!

    @Test
    fun aNewDeviceAsksTheAdmin() {
        assertEquals(Precheck.AskAdmin, InviteRules.precheck(null, joiner(a), empty, DeviceRole.CHILD))
    }

    @Test
    fun secondJoinerWithTheSameTokenIsRejected() {
        assertEquals(Precheck.Reject(PairReject.Reason.BAD_TOKEN), InviteRules.precheck(a.deviceId, joiner(b), empty, DeviceRole.CHILD))
    }

    @Test
    fun anAlreadyAddedDeviceIsWelcomedAgain() {
        val s = Membership.compute(genesis.groupId, listOf(genesis.entry, mom.addMember(a.sign.publicKey, a.dh.publicKey, "a", DeviceRole.CHILD)))!!
        assertEquals(Precheck.Rewelcome, InviteRules.precheck(a.deviceId, joiner(a), s, DeviceRole.CHILD))
        assertEquals(Precheck.Rewelcome, InviteRules.precheck(null, joiner(a), s, DeviceRole.CHILD))
        assertEquals(Precheck.Reject(PairReject.Reason.REASON_UNSPECIFIED), InviteRules.precheck(null, joiner(a), s, DeviceRole.PARENT))
    }

    @Test
    fun aRemovedDeviceCannotComeBack() {
        val add = mom.addMember(a.sign.publicKey, a.dh.publicKey, "a", DeviceRole.CHILD)
        val s = Membership.compute(genesis.groupId, listOf(genesis.entry, add, mom.removeMemberEntry(a.deviceId, 0)))!!
        assertEquals(Precheck.Reject(PairReject.Reason.ALREADY_REMOVED), InviteRules.precheck(null, joiner(a), s, DeviceRole.CHILD))
    }

    @Test
    fun aMemberWithAnotherKeyIsRefused() {
        val s = Membership.compute(genesis.groupId, listOf(genesis.entry, mom.addMember(a.sign.publicKey, b.dh.publicKey, "a", DeviceRole.CHILD)))!!
        assertEquals(Precheck.Reject(PairReject.Reason.REASON_UNSPECIFIED), InviteRules.precheck(null, joiner(a), s, DeviceRole.CHILD))
    }
}
