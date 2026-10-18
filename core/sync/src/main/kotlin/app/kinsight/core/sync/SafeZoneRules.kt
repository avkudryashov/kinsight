package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipLimits
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of a `SafeZone` entry (spec §8.1, roadmap Y4), checked on receipt by [EntryValidator]. Author-anchored like
 * [CategoryOverrideRules]: a child's is skipped; validity never changes with later membership ops. Which version counts
 * (an active ADMIN parent's, spec «только администраторы», highest rank, then author hex) is decided when reading
 * (core/data SafeZones).
 */
object SafeZoneRules {
    const val MAX_BODY_BYTES = 1024
    const val MIN_RADIUS_M = 50
    const val MAX_RADIUS_M = 5000
    const val MAX_RANK = CategoryOverrideRules.MAX_RANK

    /** Null when valid, otherwise the reason; a violation is [Verdict.Skipped], never counted against the peer. */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val z = e.body.safe_zone ?: return "not a safe zone"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role == DeviceRole.CHILD) return "safe zone from a child"
        if (e.bodySize > MAX_BODY_BYTES) return "safe zone over $MAX_BODY_BYTES bytes"
        if (z.zone_id.size != 16) return "zone id of ${z.zone_id.size} bytes"
        if (!nameOk(z.name)) return "bad name"
        if (!LocationRules.coordinatesOk(z.lat_e7, z.lon_e7)) return "centre out of range"
        if (z.radius_m !in MIN_RADIUS_M..MAX_RADIUS_M) return "radius ${z.radius_m}"
        if (z.rank !in 0..MAX_RANK) return "rank ${z.rank} out of range"
        return null
    }

    /** Not blank, at most 64 code points and 256 UTF-8 bytes (the display-name caps). */
    fun nameOk(name: String): Boolean = name.isNotBlank() && MembershipLimits.nameOk(name)
}
