package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of an `AppRule` entry (spec §4), checked on receipt by [EntryValidator]. Author-anchored like [SafeZoneRules]:
 * a child's is skipped; validity never changes with later membership ops. Which version counts (the live one per author,
 * child and target, highest rank) and whether its author may rule at all is decided when reading (core/data).
 */
object AppRuleRules {
    const val MAX_BODY_BYTES = 1024

    /** Daily allowances are whole quarters of an hour, from one step up to 12 hours. */
    const val STEP_MS = 900_000L
    const val MIN_MS = STEP_MS
    const val MAX_MS = 43_200_000L
    const val MAX_RANK = CategoryOverrideRules.MAX_RANK

    /** Null when valid, otherwise the reason; a violation is [Verdict.Skipped], never counted against the peer. */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val r = e.body.app_rule ?: return "not an app rule"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "app rule from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "app rule over $MAX_BODY_BYTES bytes"
        if (r.child_id.size != 16) return "child id of ${r.child_id.size} bytes"
        if (r.rank !in 0..MAX_RANK) return "rank ${r.rank} out of range"
        return shapeReason(r.target, r.mode, r.package_name, r.category, r.limit_ms, r.block_on_limit)
    }

    /** Whether the fields make a rule the child can enforce; the same check [violation] applies, for editors to share. */
    fun shapeOk(target: AppRule.Target, mode: AppRule.Mode, packageName: String, category: Int, limitMs: Long, blockOnLimit: Boolean): Boolean =
        shapeReason(target, mode, packageName, category, limitMs, blockOnLimit) == null

    private fun shapeReason(target: AppRule.Target, mode: AppRule.Mode, packageName: String, category: Int, limitMs: Long, blockOnLimit: Boolean): String? {
        when (target) {
            AppRule.Target.TOTAL -> if (mode != AppRule.Mode.LIMIT) return "total only limits"
            AppRule.Target.CATEGORY -> if (mode == AppRule.Mode.ALLOW) return "allow only apps"
            AppRule.Target.APP -> Unit
            else -> return "unknown target"
        }
        if (mode != AppRule.Mode.LIMIT && mode != AppRule.Mode.BLOCK && mode != AppRule.Mode.ALLOW) return "unknown mode"
        if (target == AppRule.Target.APP) {
            if (!CategoryOverrideRules.packageOk(packageName)) return "bad package"
        } else if (packageName.isNotEmpty()) {
            return "package without APP"
        }
        if (target == AppRule.Target.CATEGORY) {
            if (category !in 1..CategoryOverrideRules.MAX_CATEGORY) return "category out of range"
        } else if (category != 0) {
            return "category without CATEGORY"
        }
        if (mode == AppRule.Mode.LIMIT) {
            if (limitMs !in MIN_MS..MAX_MS || limitMs % STEP_MS != 0L) return "limit off the step"
        } else {
            if (limitMs != 0L) return "limit without LIMIT"
            if (blockOnLimit) return "block on limit without LIMIT"
        }
        return null
    }
}
