package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.DeviceRole

/**
 * Validity of a `Location` entry (spec §8), checked on receipt by [EntryValidator]. Author-anchored like
 * [CategoryOverrideRules]: only a CHILD signs one (unspecified reads as PARENT), so a parent's position is never shared.
 * Either a point (coordinates in range, no zone) or «in zone N» (a 16-byte zone_id and no coordinates, spec §8.1).
 */
object LocationRules {
    const val MAX_BODY_BYTES = 256
    const val MAX_LAT_E7 = 900_000_000
    const val MAX_LON_E7 = 1_800_000_000

    /** Null when valid, otherwise the reason; a violation is [Verdict.Skipped], never counted against the peer. */
    fun violation(e: DecodedEntry, membership: MembershipState): String? {
        val l = e.body.location ?: return "not a location"
        val role = membership.roleOf(e.author) ?: return "unknown author"
        if (role != DeviceRole.CHILD) return "location from a parent"
        if (e.bodySize > MAX_BODY_BYTES) return "location over $MAX_BODY_BYTES bytes"
        if (l.fix_time_ms <= 0) return "fix time ${l.fix_time_ms}"
        if (l.zone_id.size != 0) {
            if (l.zone_id.size != 16) return "zone id of ${l.zone_id.size} bytes"
            if (l.lat_e7 != 0 || l.lon_e7 != 0 || l.accuracy_m != 0) return "coordinates with a zone"
            return null
        }
        if (!coordinatesOk(l.lat_e7, l.lon_e7)) return "coordinates out of range"
        // uint32 on the wire: a value of 2^31 or more reads negative here.
        if (l.accuracy_m < 0) return "accuracy out of range"
        return null
    }

    fun coordinatesOk(latE7: Int, lonE7: Int): Boolean = latE7 in -MAX_LAT_E7..MAX_LAT_E7 && lonE7 in -MAX_LON_E7..MAX_LON_E7
}
