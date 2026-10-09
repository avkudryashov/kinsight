package app.kinsight.core.model

import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.MembershipOp
import okio.ByteString.Companion.toByteString

/** Creates a signed AddMember op; an over-cap [displayName] is refused before anything is signed. */
fun EntryAuthor.addMember(signPublicKey: ByteArray, dhPublicKey: ByteArray, displayName: String): DecodedEntry {
    require(MembershipLimits.nameOk(displayName)) { "display name over ${MembershipLimits.MAX_NAME_CODE_POINTS} code points" }
    val entry = create {
        it.copy(membership_op = MembershipOp(add = AddMember(signPublicKey.toByteString(), dhPublicKey.toByteString(), displayName)))
    }
    check(MembershipLimits.withinLimits(entry)) { "membership entry over limits" }
    return entry
}
