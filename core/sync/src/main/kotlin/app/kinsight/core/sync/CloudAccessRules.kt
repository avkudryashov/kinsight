package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of a `CloudAccess` entry (spec §6), checked on receipt by [EntryValidator]. Author-anchored like
 * [CategoryOverrideRules]: any parent may sign one and validity never changes with later membership ops; which entry is
 * used (the current StorageOwner's, for the current generation) is decided when reading, in core/transport-cloud.
 */
object CloudAccessRules {
    const val MAX_BODY_BYTES = 16 * 1024
    const val MAX_CIPHERTEXT_BYTES = 4096

    /** Null when valid, otherwise the reason; a violation is [Verdict.Skipped], never counted against the peer. */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val a = e.body.cloud_access ?: return "not a cloud access"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "cloud access from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "cloud access over $MAX_BODY_BYTES bytes"
        return fieldViolation(a)
    }

    /**
     * The checks on the record itself, for a writer to run before it signs (a signed seq cannot be taken back): with a
     * ciphertext within [MAX_CIPHERTEXT_BYTES] the body stays far below [MAX_BODY_BYTES].
     */
    fun fieldViolation(a: CloudAccess): String? {
        if (a.generation < 1) return "generation ${a.generation}"
        if (a.storage_id.size != 16) return "storage id of ${a.storage_id.size} bytes"
        if (a.nonce.size != 12) return "nonce of ${a.nonce.size} bytes"
        if (a.ciphertext.size !in 17..MAX_CIPHERTEXT_BYTES) return "ciphertext of ${a.ciphertext.size} bytes"
        return null
    }
}
