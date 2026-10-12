package app.kinsight.core.model

import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.EntryBody
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import app.kinsight.core.model.proto.SetAdmin
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString

/**
 * Mutable wall clock plus a Lamport source shared by all authors of a test group.
 * [observe] reports the highest Lamport value created so far in the group and accounts for the entry about to be
 * created, so every honest author has observed every earlier entry: causal order equals creation order.
 */
class TestClock(var now: Long = 1_000L) {
    var lamport: Long = 1L // the genesis entry
    fun tick(): Long = ++now
    fun observe(): Long = lamport++
}

class TestGroup(creatorName: String = "creator") {
    val clock = TestClock()
    val creatorKeys = DeviceKeys.generate()
    val genesis = GroupGenesis.create(creatorKeys, creatorName, clock::tick)
    val groupId = genesis.groupId
    val creator = EntryAuthor(groupId, creatorKeys, lastSeq = 1, clock = clock::tick, lastLamport = 1, observedLamport = clock::observe)

    fun newAuthor(keys: DeviceKeys = DeviceKeys.generate()) =
        EntryAuthor(groupId, keys, lastSeq = 0, clock = clock::tick, observedLamport = clock::observe)
}

/** Creates an entry whose Lamport value is forced to [lamport] when given (an adversarial author). */
private fun EntryAuthor.op(lamport: Long?, fill: (EntryBody) -> EntryBody) = create {
    val body = fill(it)
    if (lamport == null) body else body.copy(lamport = lamport)
}

fun EntryAuthor.addMember(keys: DeviceKeys, name: String = "member", lamport: Long? = null) = op(lamport) {
    it.copy(
        membership_op = MembershipOp(
            add = AddMember(
                sign_public_key = keys.sign.publicKey.toByteString(),
                dh_public_key = keys.dh.publicKey.toByteString(),
                display_name = name,
            ),
        ),
    )
}

fun EntryAuthor.removeMember(id: DeviceId, lastSeq: Long, lamport: Long? = null) = op(lamport) {
    it.copy(membership_op = MembershipOp(remove = RemoveMember(device_id = id.bytes().toByteString(), last_seq = lastSeq)))
}

fun EntryAuthor.setAdmin(id: DeviceId, admin: Boolean, lastSeq: Long = 0, lamport: Long? = null) = op(lamport) {
    it.copy(membership_op = MembershipOp(set_admin = SetAdmin(device_id = id.bytes().toByteString(), admin = admin, last_seq = lastSeq)))
}

fun EntryAuthor.leave(lamport: Long? = null) = op(lamport) { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
