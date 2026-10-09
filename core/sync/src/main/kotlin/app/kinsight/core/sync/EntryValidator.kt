package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipState

sealed interface Verdict {
    data object Accept : Verdict
    data object Duplicate : Verdict
    data object Expired : Verdict
    data object Unwanted : Verdict
    data class Invalid(val reason: String) : Verdict
}

class EntryValidator(
    private val groupId: GroupId,
    private val membership: MembershipState,
    private val policy: SyncPolicy,
) {
    /** [alreadyAccounted]: the receiver has already accounted for (author, seq) of [e]; see EntryStore. */
    fun check(e: DecodedEntry, alreadyAccounted: Boolean): Verdict {
        if (e.groupId != groupId) return Verdict.Invalid("foreign group")
        if (e.seq < 1) return Verdict.Invalid("non-positive seq")
        if (!withinMembershipLimits(e)) return Verdict.Invalid("oversized membership entry")
        val key = membership.signKeyOf(e.author) ?: return Verdict.Invalid("unknown author")
        if (!membership.acceptsEntryFrom(e.author, e.seq)) return Verdict.Invalid("after membership cutoff")
        if (!e.verifySignature(key)) return Verdict.Invalid("bad signature")
        if (!policy.acceptsFromPeers(e)) return Verdict.Unwanted
        if (alreadyAccounted) return Verdict.Duplicate
        if (policy.isExpired(e)) return Verdict.Expired
        return Verdict.Accept
    }

    companion object {
        const val MAX_MEMBERSHIP_ENTRY_BYTES = 8 * 1024
        const val MAX_DISPLAY_NAME_BYTES = 64

        /** Size caps for membership-relevant entries (others pass): encoded size and display-name length. */
        fun withinMembershipLimits(e: DecodedEntry): Boolean {
            if (!e.isMembershipRelevant) return true
            if (e.bytes.size > MAX_MEMBERSHIP_ENTRY_BYTES) return false
            val op = e.body.membership_op ?: return true
            val name = op.create?.display_name ?: op.add?.display_name ?: return true
            return name.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES
        }
    }
}
