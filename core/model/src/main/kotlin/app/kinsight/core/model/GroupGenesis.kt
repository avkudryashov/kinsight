package app.kinsight.core.model

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.proto.CreateGroup
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.MembershipOp
import okio.ByteString.Companion.toByteString

class Genesis(val groupId: GroupId, val entry: DecodedEntry)

object GroupGenesis {
    fun create(
        keys: DeviceKeys,
        displayName: String,
        clock: () -> Long = System::currentTimeMillis,
        role: DeviceRole = DeviceRole.PARENT,
    ): Genesis {
        require(MembershipLimits.nameOk(displayName)) { "display name over ${MembershipLimits.MAX_NAME_CODE_POINTS} code points" }
        val salt = Primitives.randomBytes(16)
        val groupId = GroupId.derive(keys.sign.publicKey, salt)
        val entry = EntryAuthor(groupId, keys, lastSeq = 0, clock).create {
            it.copy(
                membership_op = MembershipOp(
                    create = CreateGroup(
                        salt = salt.toByteString(),
                        sign_public_key = keys.sign.publicKey.toByteString(),
                        dh_public_key = keys.dh.publicKey.toByteString(),
                        display_name = displayName,
                        role = role,
                    ),
                ),
            )
        }
        return Genesis(groupId, entry)
    }
}
