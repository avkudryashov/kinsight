package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.CategoryOverride

/** The single own-log entry point (UsagePipeline.appendOwn under the pipeline lock); false without a group. */
typealias OwnAppend = suspend (build: (EntryAuthor) -> List<DecodedEntry>) -> Boolean

/** An app's effective shared category and the parent whose entry set it. */
data class EffectiveCategory(val category: AppCategory, val author: DeviceId)

/**
 * The family's shared category overrides (spec §4.4, stage 5c), derived from `CategoryOverride` entries. Per package
 * the entry of an ACTIVE parent with the largest (Lamport, author hex) wins; entries of departed or removed parents
 * (still valid on receipt), of children and of unknown authors never count. A winner with category 0 clears the
 * override. Every parent computes the same map from the same entries.
 */
object CategoryOverrides {
    /** Wire value: ordinal + 1, 0 = cleared. Kept equal to CategoryOverrideRules.MAX_CATEGORY at the top (tested). */
    fun wire(c: AppCategory?): Int = c?.let { it.ordinal + 1 } ?: 0

    /** Null for 0 (cleared) and for a value out of range. */
    fun fromWire(v: Int): AppCategory? = AppCategory.entries.getOrNull(v - 1)

    /**
     * The winning entry per package, cleared winners included (a cleared winner hides older choices). Entries with a
     * category out of range are skipped before choosing (they are invalid on receipt anyway).
     */
    fun winners(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, DecodedEntry> =
        entries.asSequence()
            .filter { it.kind == EntryKind.CATEGORY_OVERRIDE && membership.active[it.author]?.isChild == false }
            .filter { o(it).category == 0 || fromWire(o(it).category) != null }
            .groupBy { o(it).package_name }
            .mapValues { (_, es) -> es.maxWith(compareBy<DecodedEntry>({ it.lamport }, { it.author.hex })) }

    fun effectiveWithAuthors(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, EffectiveCategory> =
        winners(entries, membership).mapNotNull { (pkg, e) -> fromWire(o(e).category)?.let { pkg to EffectiveCategory(it, e.author) } }.toMap()

    fun effective(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, AppCategory> =
        effectiveWithAuthors(entries, membership).mapValues { it.value.category }

    /** The snapshot's map; empty for a device that is no longer a member. */
    fun effective(s: GroupSnapshot.Ready): Map<String, AppCategory> =
        if (s.removed) emptyMap() else effective(s.categoryOverrides, s.membership)

    private fun o(e: DecodedEntry): CategoryOverride = e.body.category_override!!
}
