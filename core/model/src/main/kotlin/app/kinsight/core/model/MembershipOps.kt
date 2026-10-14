package app.kinsight.core.model

import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import app.kinsight.core.model.proto.SetAdmin
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.StorageOwner
import okio.ByteString.Companion.toByteString

/** Creates a signed AddMember op; [groupKeyEpoch] is the key generation the joiner is handed at pairing; an over-cap [displayName] is refused before anything is signed. */
fun EntryAuthor.addMember(
    signPublicKey: ByteArray,
    dhPublicKey: ByteArray,
    displayName: String,
    role: DeviceRole = DeviceRole.PARENT,
    groupKeyEpoch: Int = 0,
): DecodedEntry {
    require(MembershipLimits.nameOk(displayName)) { "display name over ${MembershipLimits.MAX_NAME_CODE_POINTS} code points" }
    val entry = create {
        it.copy(membership_op = MembershipOp(add = AddMember(signPublicKey.toByteString(), dhPublicKey.toByteString(), displayName, role, groupKeyEpoch)))
    }
    check(MembershipLimits.withinLimits(entry)) { "membership entry over limits" }
    return entry
}

/** RemoveMember: entries of [id] with seq above [lastSeq] are void everywhere (spec §4.5). */
fun EntryAuthor.removeMemberEntry(id: DeviceId, lastSeq: Long): DecodedEntry = create {
    it.copy(membership_op = MembershipOp(remove = RemoveMember(device_id = id.bytes().toByteString(), last_seq = lastSeq)))
}

/** SetAdmin(admin = true); ignored by Membership for a CHILD. Revoking is final and not offered by the app. */
fun EntryAuthor.grantAdminEntry(id: DeviceId): DecodedEntry = create {
    it.copy(membership_op = MembershipOp(set_admin = SetAdmin(device_id = id.bytes().toByteString(), admin = true)))
}

/** StateEvent LEFT_GROUP: membership-relevant, visible to everyone (spec §9.5). */
fun EntryAuthor.leaveGroupEntry(): DecodedEntry = create {
    it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP))
}

/** StorageOwner: [owner]'s Disk holds the group's cloud storage [storageId] from [generation] on (spec §4). */
fun EntryAuthor.storageOwnerEntry(owner: DeviceId, storageId: ByteArray, generation: Long): DecodedEntry = create {
    it.copy(
        membership_op = MembershipOp(
            storage_owner = StorageOwner(owner.bytes().toByteString(), storageId.toByteString(), generation),
        ),
    )
}
