package app.kinsight.core.data

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.WireConstants
import app.kinsight.core.model.proto.StateEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** One fix of a child (spec §8): degrees × 10^7, [timeMs] = the phone's Location.time. Never logged. */
data class GeoPoint(val latE7: Int, val lonE7: Int, val accuracyM: Int, val timeMs: Long) {
    override fun toString() = "GeoPoint(±${accuracyM}m)"
}

/** Where a child is by its newest word (a Location entry or a zone event, by seq). [atMs] is the child's time of it. */
sealed interface PlaceStatus {
    val atMs: Long

    /** «В зоне N» (spec §8.1); [zoneName] null for a zone this phone does not know (any more). */
    data class InZone(val zoneId: String, val zoneName: String?, override val atMs: Long) : PlaceStatus {
        override fun toString() = "InZone(${zoneId.take(8)})"
    }

    /** Outside every zone; [point] the newest word's fix, null when that word is an exit without a fix yet. */
    data class Outside(val point: GeoPoint?, override val atMs: Long) : PlaceStatus
}

/** The location screen of one child (spec §8, §12): the place, the last point, today's route, the zones, today's zone events. */
data class ChildLocation(
    val member: MemberView,
    val status: PlaceStatus?,
    val last: GeoPoint?,
    /** Today's fixes (the viewer's day), in the child's order. */
    val route: List<GeoPoint>,
    val zones: List<ZoneView>,
    /** Today's ZONE_ENTER and ZONE_EXIT, newest first. */
    val zoneEvents: List<EventView>,
)

object LocationReadModel {
    /** Spec §9: a parent reads more often while a child is outside, as long as the child's word is this fresh. */
    const val FOLLOW_FOR_MS = 30 * 60_000L

    /** The fix of a point entry; null for «in zone» and for other kinds. */
    fun point(e: DecodedEntry): GeoPoint? =
        e.body.location?.takeIf { it.zone_id.size == 0 }?.let { GeoPoint(it.lat_e7, it.lon_e7, it.accuracy_m, it.fix_time_ms) }

    /**
     * The child's place by its newest word: its Location entries and zone events, ordered by seq (spec §8: by Lamport and
     * seq; one author, so seq). [names] by zone hex (SafeZones.names).
     */
    fun status(locations: List<DecodedEntry>, events: List<DecodedEntry>, child: DeviceId, names: Map<String, String>): PlaceStatus? {
        val words = locations.filter { it.author == child && it.kind == EntryKind.LOCATION } +
            events.filter { e ->
                e.author == child && e.body.state_event?.let { it.kind in WireConstants.ZONE_KINDS && it.zone_id.size == 16 } == true
            }
        val newest = words.maxByOrNull { it.seq } ?: return null
        newest.body.location?.let { l ->
            if (l.zone_id.size == 16) {
                val id = l.zone_id.hex()
                return PlaceStatus.InZone(id, names[id], l.fix_time_ms)
            }
            return PlaceStatus.Outside(point(newest), l.fix_time_ms)
        }
        val ev = newest.body.state_event!!
        val id = ev.zone_id.hex()
        return if (ev.kind == StateEvent.Kind.ZONE_ENTER) PlaceStatus.InZone(id, names[id], ev.from_ms) else PlaceStatus.Outside(null, ev.from_ms)
    }

    /** Null unless [id] is an active child of the snapshot. [today] in [zone] is the viewer's day. */
    fun childLocation(s: GroupSnapshot.Ready, id: DeviceId, today: LocalDate, zone: ZoneId, nowMs: Long): ChildLocation? {
        if (s.membership.active[id]?.isChild != true) return null
        val member = SnapshotViews.members(s, nowMs).firstOrNull { it.id == id } ?: return null
        val names = SafeZones.names(s.safeZones, s.membership)
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val points = s.locations.filter { it.author == id }.sortedBy { it.seq }.mapNotNull(::point)
        val events = UsageReadModel.events(s.events.filter { it.author == id }, names)
            .filter { it.kind in WireConstants.ZONE_KINDS && day(it.atMs) == today }
            .sortedByDescending { it.seq }
        return ChildLocation(
            member = member,
            status = status(s.locations, s.events, id, names),
            last = points.lastOrNull(),
            route = points.filter { day(it.timeMs) == today },
            zones = SafeZones.effective(s),
            zoneEvents = events,
        )
    }

    /** Spec §9: on a parent's phone, some active child is outside its zones and its word is under [FOLLOW_FOR_MS] old. */
    fun followNeeded(s: GroupSnapshot.Ready, nowMs: Long): Boolean {
        if (s.removed || s.membership.active[s.self]?.isChild != false) return false
        return s.membership.active.values.filter { it.isChild }.any { c ->
            val st = status(s.locations, s.events, c.id, emptyMap())
            st is PlaceStatus.Outside && abs(nowMs - st.atMs) <= FOLLOW_FOR_MS
        }
    }
}
