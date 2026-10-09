package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipLimits
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
        if (!MembershipLimits.withinLimits(e)) return Verdict.Invalid("over MembershipLimits")
        val key = membership.signKeyOf(e.author) ?: return Verdict.Invalid("unknown author")
        if (!membership.acceptsEntryFrom(e.author, e.seq)) return Verdict.Invalid("after membership cutoff")
        if (!e.verifySignature(key)) return Verdict.Invalid("bad signature")
        if (!policy.acceptsFromPeers(e)) return Verdict.Unwanted
        if (alreadyAccounted) return Verdict.Duplicate
        if (policy.isExpired(e)) return Verdict.Expired
        return Verdict.Accept
    }
}
