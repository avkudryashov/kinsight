package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.proto.SafeZone
import app.kinsight.core.sync.SafeZoneRules

/** A live safe zone (spec §8.1); [id] is the zone_id in hex. Never logged: it carries a place of the family. */
data class ZoneView(val id: String, val name: String, val latE7: Int, val lonE7: Int, val radiusM: Int, val author: DeviceId) {
    override fun toString() = "ZoneView(${id.take(8)}, r=$radiusM)"
}

/**
 * The family's safe zones from the SafeZone entries, resolved like [CategoryOverrides] (roadmap Y4): per zone_id the entry
 * of an ACTIVE ADMIN parent (spec §8.1 «Авторы — только администраторы») with the largest (rank, author hex) wins;
 * children's, non-admins', departed parents' and entries the rules skip never count. A winner with `deleted` hides the
 * zone; its name stays known for old events. Every phone, the child's included, computes the same list from the same
 * entries.
 */
object SafeZones {
    fun winners(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, DecodedEntry> =
        entries.asSequence()
            .filter { it.kind == EntryKind.SAFE_ZONE && membership.active[it.author]?.let { m -> !m.isChild && m.isAdmin } == true }
            .filter { SafeZoneRules.violation(it, membership) == null }
            .groupBy { z(it).zone_id.hex() }
            .mapValues { (_, es) -> es.maxWith(compareBy<DecodedEntry>({ z(it).rank }, { it.author.hex })) }

    /** The zones in force, by name. */
    fun effective(entries: Collection<DecodedEntry>, membership: MembershipState): List<ZoneView> =
        winners(entries, membership).values.filter { !z(it).deleted }.map(::view)
            .sortedWith(compareBy<ZoneView>({ it.name.lowercase() }, { it.id }))

    /** The snapshot's zones; empty for a device that is no longer a member. */
    fun effective(s: GroupSnapshot.Ready): List<ZoneView> = if (s.removed) emptyList() else effective(s.safeZones, s.membership)

    /** Every zone's name by hex id, deleted ones included, so an old event still names its zone. */
    fun names(entries: Collection<DecodedEntry>, membership: MembershipState): Map<String, String> =
        winners(entries, membership).mapValues { z(it.value).name }

    /** The rank of a new version over [winner] (the zone's current winner, if any); saturates at SafeZoneRules.MAX_RANK. */
    fun nextRank(winner: DecodedEntry?): Long = minOf((winner?.let { z(it).rank } ?: 0L) + 1, SafeZoneRules.MAX_RANK)

    private fun view(e: DecodedEntry): ZoneView = z(e).let { ZoneView(it.zone_id.hex(), it.name, it.lat_e7, it.lon_e7, it.radius_m, e.author) }

    private fun z(e: DecodedEntry): SafeZone = e.body.safe_zone!!
}
