package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState

sealed interface Verdict {
    data object Accept : Verdict
    data object Duplicate : Verdict
    data object Expired : Verdict
    data object Unwanted : Verdict

    /** Fails a safety check (group, seq, limits, author, cutoff, signature): counts towards TOO_MANY_INVALID. */
    data class Invalid(val reason: String) : Verdict

    /**
     * Validly signed by a member but against a rule about what the entry means (CategoryOverrideRules). Such rules can
     * differ between app versions, so this is never held against the peer that relayed it (audit A-1): not stored, not
     * counted, and its seq stays unaccounted, a gap only the author closes (spec §5).
     */
    data class Skipped(val reason: String) : Verdict
}

class EntryValidator(
    private val groupId: GroupId,
    private val membership: MembershipState,
    private val policy: SyncPolicy,
) {
    /** [alreadyAccounted]: the receiver has already accounted for (author, seq) of [e]; see EntryStore. */
    fun check(e: DecodedEntry, alreadyAccounted: Boolean): Verdict {
        // Safety: who wrote it, for which group, within which limits. Version independent.
        if (e.groupId != groupId) return Verdict.Invalid("foreign group")
        if (e.seq < 1) return Verdict.Invalid("non-positive seq")
        if (!MembershipLimits.withinLimits(e)) return Verdict.Invalid("over MembershipLimits")
        val key = membership.signKeyOf(e.author) ?: return Verdict.Invalid("unknown author")
        if (!membership.acceptsEntryFrom(e.author, e.seq)) return Verdict.Invalid("after membership cutoff")
        if (!e.verifySignature(key)) return Verdict.Invalid("bad signature")
        // Meaning: rules of known kinds. Unknown kinds and unknown enum values pass (they decode as UNKNOWN/default).
        if (e.kind == EntryKind.CATEGORY_OVERRIDE) CategoryOverrideRules.violation(e, membership)?.let { return Verdict.Skipped(it) }
        if (e.kind == EntryKind.CLOUD_ACCESS) CloudAccessRules.violation(e, membership)?.let { return Verdict.Skipped(it) }
        if (e.kind == EntryKind.STORAGE_HANDOVER) StorageHandoverRules.violation(e, membership)?.let { return Verdict.Skipped(it) }
        if (e.kind == EntryKind.LOCATION) LocationRules.violation(e, membership)?.let { return Verdict.Skipped(it) }
        if (e.kind == EntryKind.SAFE_ZONE) SafeZoneRules.violation(e, membership)?.let { return Verdict.Skipped(it) }
        if (!policy.acceptsFromPeers(e)) return Verdict.Unwanted
        if (alreadyAccounted) return Verdict.Duplicate
        if (policy.isExpired(e)) return Verdict.Expired
        return Verdict.Accept
    }
}
