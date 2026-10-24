package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.sync.AppRuleRules
import okio.ByteString.Companion.toByteString

/**
 * The family's app rules (spec §4) from `AppRule` entries, resolved like [SafeZones] and [CategoryOverrides]: per
 * (child, target) ([ThresholdRule.idOf]) the entry with the largest (rank, author hex) wins among the entries an ACTIVE
 * parent signed (any parent, not only admins); children's, unknown authors' and entries the rules skip never count. A
 * winner with `deleted` hides the rule. Every phone, the child's included, computes the same list from the same entries.
 */
object AppRules {
    /** The winning entry per rule id, deleted winners included (a tombstone hides older versions). */
    fun winners(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, DecodedEntry> =
        entries.asSequence()
            .filter { it.kind == EntryKind.APP_RULE && membership.active[it.author]?.isChild == false }
            .filter { AppRuleRules.violation(it, membership) == null }
            .mapNotNull { e -> toRule(e)?.let { it.id to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, es) -> es.maxWith(compareBy<DecodedEntry>({ r(it).rank }, { it.author.hex })) }

    /** The rules in force, by id. */
    fun effective(entries: Collection<DecodedEntry>, membership: MembershipState): List<ThresholdRule> =
        winners(entries, membership).values.filter { !r(it).deleted }.mapNotNull(::toRule).sortedBy { it.id }

    /** The snapshot's rules; empty for a device that is no longer a member. */
    fun effective(s: GroupSnapshot.Ready): List<ThresholdRule> = if (s.removed) emptyList() else effective(s.appRules, s.membership)

    /** The rank of a new version over [winner] (the rule's current winner, if any); saturates at AppRuleRules.MAX_RANK. */
    fun nextRank(winner: DecodedEntry?): Long = minOf((winner?.let { r(it).rank } ?: 0L) + 1, AppRuleRules.MAX_RANK)

    /** The rule [e] states; null for a shape `AppRuleRules.shapeOk` refuses, a child id that is not 16 bytes or an unknown category. */
    fun toRule(e: DecodedEntry): ThresholdRule? {
        val a = e.body.app_rule ?: return null
        if (a.child_id.size != 16) return null
        val child = DeviceId.fromBytes(a.child_id.toByteArray())
        val target = when (a.target) {
            AppRule.Target.TOTAL -> ThresholdTarget.Total
            AppRule.Target.CATEGORY -> CategoryOverrides.fromWire(a.category)?.let(ThresholdTarget::Category) ?: return null
            AppRule.Target.APP -> ThresholdTarget.App(a.package_name)
            else -> return null
        }
        val rule = when (a.mode) {
            AppRule.Mode.LIMIT -> ThresholdRule.of(child, target, a.limit_ms, a.block_on_limit)
            AppRule.Mode.BLOCK -> ThresholdRule.block(child, target)
            AppRule.Mode.ALLOW -> (target as? ThresholdTarget.App)?.let { ThresholdRule.allow(child, it.packageName) }
            else -> null
        } ?: return null
        // block()/allow() build with zero limit; a stray limit or flag on those is a shape shapeOk refuses.
        if (!AppRuleRules.shapeOk(a.target, a.mode, a.package_name, a.category, a.limit_ms, a.block_on_limit)) return null
        return rule.copy(author = e.author)
    }

    /** The wire fields of [rule]: the inverse of [toRule], without `deleted` and `rank`. */
    fun wire(rule: ThresholdRule): AppRule {
        val (target, pkg, cat) = when (val t = rule.target) {
            ThresholdTarget.Total -> Triple(AppRule.Target.TOTAL, "", 0)
            is ThresholdTarget.Category -> Triple(AppRule.Target.CATEGORY, "", CategoryOverrides.wire(t.category))
            is ThresholdTarget.App -> Triple(AppRule.Target.APP, t.packageName, 0)
        }
        return AppRule(
            child_id = rule.child.bytes().toByteString(), target = target, package_name = pkg, category = cat,
            mode = rule.mode.toWire(), limit_ms = rule.limitMs, block_on_limit = rule.blockOnLimit,
        )
    }

    private fun r(e: DecodedEntry): AppRule = e.body.app_rule!!
}
