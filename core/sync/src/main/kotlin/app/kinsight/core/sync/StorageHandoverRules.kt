package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of a `StorageHandover` entry (spec §7.1), checked on receipt by [EntryValidator]. Author-anchored like
 * [CloudAccessRules]: any parent may sign one; which one counts (the current owner's, for the current generation) is
 * decided when reading (core/transport-cloud OwnerLifecycle).
 */
object StorageHandoverRules {
    const val MAX_BODY_BYTES = 1024

    /** Null when valid, otherwise the reason; a violation is [Verdict.Skipped], never counted against the peer. */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val h = e.body.storage_handover ?: return "not a storage handover"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "storage handover from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "storage handover over $MAX_BODY_BYTES bytes"
        if (h.to_device_id.size != 16) return "device id of ${h.to_device_id.size} bytes"
        if (h.generation < 1) return "generation ${h.generation}"
        return null
    }
}
