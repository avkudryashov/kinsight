package app.kinsight.core.model

import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import app.kinsight.core.model.proto.SetAdmin
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString

/** Mutable clock shared by all authors of a test group. */
class TestClock(var now: Long = 1_000L) {
    fun tick(): Long = ++now
}

class TestGroup(creatorName: String = "creator") {
    val clock = TestClock()
    val creatorKeys = DeviceKeys.generate()
    val genesis = GroupGenesis.create(creatorKeys, creatorName, clock::tick)
    val groupId = genesis.groupId
    val creator = EntryAuthor(groupId, creatorKeys, lastSeq = 1, clock = clock::tick)

    fun newAuthor(keys: DeviceKeys = DeviceKeys.generate()) = EntryAuthor(groupId, keys, lastSeq = 0, clock = clock::tick)
}

fun EntryAuthor.addMember(keys: DeviceKeys, name: String = "member") = create {
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

fun EntryAuthor.removeMember(id: DeviceId, lastSeq: Long) = create {
    it.copy(membership_op = MembershipOp(remove = RemoveMember(device_id = id.bytes().toByteString(), last_seq = lastSeq)))
}

fun EntryAuthor.setAdmin(id: DeviceId, admin: Boolean, lastSeq: Long = 0) = create {
    it.copy(membership_op = MembershipOp(set_admin = SetAdmin(device_id = id.bytes().toByteString(), admin = admin, last_seq = lastSeq)))
}

fun EntryAuthor.leave() = create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
