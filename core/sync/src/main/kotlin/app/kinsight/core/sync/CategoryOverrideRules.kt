package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of a `CategoryOverride` entry (spec §4.4, stage 5c), checked on receipt by [EntryValidator]. Every rule
 * depends only on the entry itself and on the author's identity in membership, so all replicas agree.
 */
object CategoryOverrideRules {
    /**
     * Largest wire category: `AppCategory` ordinal + 1 (core/data `AppCategory`, 9 entries GAMES..OTHER), 0 = cleared.
     * core/sync does not depend on core/data; keep this in step when AppCategory grows (a new category also needs a
     * protocol version bump, since older replicas would reject it as invalid).
     */
    const val MAX_CATEGORY = 9
    const val MAX_BODY_BYTES = 1024
    const val MAX_PACKAGE_BYTES = 255

    /** The remainder bucket of usage summaries (core/data `Categories.REMAINDER_PACKAGE`); not an app. */
    const val REMAINDER_PACKAGE = "~other"

    /**
     * Null when valid, otherwise the reason. Author-anchored: the role is looked up by the signing author's id, never
     * read from the payload. The author must be a parent member (role other than CHILD; unspecified reads as PARENT,
     * spec §4.8); an unknown author and entries past a departed member's cutoff are already invalid in
     * [EntryValidator]. A departed parent's earlier overrides stay valid, so validity never changes with later
     * membership ops (a relayed copy cannot count towards TOO_MANY_INVALID); the read model ignores them (spec §4.4).
     */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val o = e.body.category_override ?: return "not a category override"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "category override from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "category override over $MAX_BODY_BYTES bytes"
        if (o.category !in 0..MAX_CATEGORY) return "category ${o.category} out of range"
        if (!packageOk(o.package_name)) return "bad package"
        return null
    }

    /** As in thresholds: not the remainder, no storage separators or whitespace, 1..255 UTF-8 bytes. */
    fun packageOk(p: String): Boolean =
        p.isNotEmpty() && p.encodeToByteArray().size <= MAX_PACKAGE_BYTES && p != REMAINDER_PACKAGE &&
            p.none { it == '/' || it == '|' || it.isWhitespace() }
}
