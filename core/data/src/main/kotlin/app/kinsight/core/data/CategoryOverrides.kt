package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.CategoryOverride
import app.kinsight.core.sync.CategoryOverrideRules

/** The single own-log entry point (UsagePipeline.appendOwn under the pipeline lock); false without a group. */
typealias OwnAppend = suspend (build: (EntryAuthor) -> List<DecodedEntry>) -> Boolean

/** An app's effective shared category and the parent whose entry set it. */
data class EffectiveCategory(val category: AppCategory, val author: DeviceId)

/**
 * The family's shared category overrides (spec §4.4, stage 5c), derived from `CategoryOverride` entries. Per package
 * the entry of an ACTIVE parent with the largest (rank, Lamport, author hex) wins; entries of departed or removed
 * parents (still valid on receipt), of children and of unknown authors never count. A winner with category 0 clears the
 * override. Every parent computes the same map from the same entries.
 *
 * `rank` = the winner's rank + 1 when the entry was written ([nextRank]), so a change always orders after the choice it
 * replaces; concurrent changes of the same winner tie on rank and fall back to (Lamport, author). Rank, not Lamport:
 * EntryAuthor observes membership Lamports only, and other parents' overrides must never move an author's clock.
 */
object CategoryOverrides {
    /** The rank of a new entry over [winner] (the package's current winner, if any); saturates at MAX_RANK. */
    fun nextRank(winner: DecodedEntry?): Long =
        minOf((winner?.let { o(it).rank } ?: 0L) + 1, CategoryOverrideRules.MAX_RANK)

    /** Wire value: ordinal + 1, 0 = cleared. Kept equal to CategoryOverrideRules.MAX_CATEGORY at the top (tested). */
    fun wire(c: AppCategory?): Int = c?.let { it.ordinal + 1 } ?: 0

    /** Null for 0 (cleared) and for a value out of range. */
    fun fromWire(v: Int): AppCategory? = AppCategory.entries.getOrNull(v - 1)

    /**
     * The winning entry per package, cleared winners included (a cleared winner hides older choices). Entries with a
     * negative category or a rank out of range are skipped before choosing (they are skipped on receipt anyway); a
     * category this build does not know competes normally and reads as the default.
     */
    fun winners(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, DecodedEntry> =
        entries.asSequence()
            .filter { it.kind == EntryKind.CATEGORY_OVERRIDE && membership.active[it.author]?.isChild == false }
            .filter { o(it).category >= 0 && o(it).rank in 0..CategoryOverrideRules.MAX_RANK }
            .groupBy { o(it).package_name }
            .mapValues { (_, es) -> es.maxWith(compareBy<DecodedEntry>({ o(it).rank }, { it.lamport }, { it.author.hex })) }

    fun effectiveWithAuthors(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, EffectiveCategory> =
        winners(entries, membership).mapNotNull { (pkg, e) -> fromWire(o(e).category)?.let { pkg to EffectiveCategory(it, e.author) } }.toMap()

    fun effective(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, AppCategory> =
        effectiveWithAuthors(entries, membership).mapValues { it.value.category }

    /** The snapshot's map; empty for a device that is no longer a member. */
    fun effective(s: GroupSnapshot.Ready): Map<String, AppCategory> =
        if (s.removed) emptyMap() else effective(s.categoryOverrides, s.membership)

    private fun o(e: DecodedEntry): CategoryOverride = e.body.category_override!!
}
