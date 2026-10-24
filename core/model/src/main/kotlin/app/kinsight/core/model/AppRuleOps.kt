package app.kinsight.core.model

import app.kinsight.core.model.proto.AppRule
import app.kinsight.core.model.proto.StateEvent
import okio.ByteString.Companion.toByteString

/** A parent's rule for [childId]'s apps (spec §4); [rank] orders it after the version it replaces. */
fun EntryAuthor.appRuleEntry(
    childId: ByteArray,
    target: AppRule.Target,
    packageName: String = "",
    category: Int = 0,
    mode: AppRule.Mode,
    limitMs: Long = 0,
    blockOnLimit: Boolean = false,
    rank: Long,
    deleted: Boolean = false,
): DecodedEntry = create {
    it.copy(
        app_rule = AppRule(
            child_id = childId.toByteString(), target = target, package_name = packageName, category = category, mode = mode,
            limit_ms = limitMs, block_on_limit = blockOnLimit, deleted = deleted, rank = rank,
        ),
    )
}

/** BLOCK_OVERRIDDEN (app blocking spec §5.2): a parent's PIN opened [packageName] on the child's phone until the end of the day. */
fun EntryAuthor.blockOverriddenEntry(packageName: String, atMs: Long): DecodedEntry = create {
    it.copy(state_event = StateEvent(kind = StateEvent.Kind.BLOCK_OVERRIDDEN, from_ms = atMs, to_ms = atMs, package_name = packageName))
}
