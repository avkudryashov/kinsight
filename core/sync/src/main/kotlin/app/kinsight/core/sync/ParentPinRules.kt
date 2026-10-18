package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * What a ParentPin must be to be stored (spec §10): signed by a parent (the admin requirement is applied when reading, as
 * for SafeZone), a 16-byte salt, a 32-byte hash and an Argon2 cost a phone can afford: a hostile cost must not exhaust
 * the child's phone that checks the PIN.
 */
object ParentPinRules {
    const val MAX_BODY_BYTES = 256
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32
    val MEMORY_KIB: IntRange = 8_192..65_536
    val ITERATIONS: IntRange = 1..4
    const val MAX_RANK = CategoryOverrideRules.MAX_RANK

    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val p = e.body.parent_pin ?: return "not a parent pin"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "parent pin from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "parent pin over $MAX_BODY_BYTES bytes"
        if (p.salt.size != SALT_BYTES) return "salt of ${p.salt.size} bytes"
        if (p.hash.size != HASH_BYTES) return "hash of ${p.hash.size} bytes"
        if (p.memory_kib !in MEMORY_KIB) return "memory ${p.memory_kib} KiB"
        if (p.iterations !in ITERATIONS) return "${p.iterations} passes"
        if (p.rank !in 0..MAX_RANK) return "rank ${p.rank} out of range"
        return null
    }
}
