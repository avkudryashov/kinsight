package app.kinsight.core.model

import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.RemoveMember
import app.kinsight.core.model.proto.SetAdmin
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.StorageHandover
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

/**
 * StorageOwner: [owner]'s Disk holds the group's cloud storage [storageId] from [generation] on (spec §4). [tokenId]
 * names the access token (fresh on a new sign-in, copied when the same token goes on); [departed] are the departed
 * members the signer knew of when that token was issued (each once, at most [MembershipLimits.MAX_OWNER_DEPARTED]).
 */
fun EntryAuthor.storageOwnerEntry(
    owner: DeviceId,
    storageId: ByteArray,
    generation: Long,
    tokenId: ByteArray = ByteArray(0),
    departed: Collection<DeviceId> = emptyList(),
): DecodedEntry {
    val ids = departed.distinct()
    require(ids.size <= MembershipLimits.MAX_OWNER_DEPARTED) { "over ${MembershipLimits.MAX_OWNER_DEPARTED} departed ids" }
    return create {
        it.copy(
            membership_op = MembershipOp(
                storage_owner = StorageOwner(
                    owner.bytes().toByteString(),
                    storageId.toByteString(),
                    generation,
                    tokenId.toByteString(),
                    ids.map { d -> d.bytes().toByteString() },
                ),
            ),
        )
    }
}

/**
 * The owner turns the cloud off for the whole family: StorageOwner(this owner, [storageId], [generation], off). Valid only
 * from the owner in force with [generation] above its op (Membership); token_id and departed stay empty.
 */
fun EntryAuthor.storageOffEntry(storageId: ByteArray, generation: Long): DecodedEntry = create {
    it.copy(
        membership_op = MembershipOp(
            storage_owner = StorageOwner(
                device_id = keys.deviceId.bytes().toByteString(),
                storage_id = storageId.toByteString(),
                generation = generation,
                off = true,
            ),
        ),
    )
}

/** StorageHandover (spec §7.1): the storage owner asks [to] to take the storage of [generation]. Not a membership op. */
fun EntryAuthor.storageHandoverEntry(to: DeviceId, generation: Long): DecodedEntry = create {
    it.copy(storage_handover = StorageHandover(to.bytes().toByteString(), generation))
}
