package app.kinsight.core.location

/** [UNKNOWN]: tracking just started, no decisive fix yet. */
enum class Mode { UNKNOWN, IN_ZONE, OUTSIDE }

/**
 * What the child's phone remembers between runs (TrackerStore): the mode, the zones it is in (hex ids), the zone
 * signatures with a proximity alert registered, and when the next outside tick is due.
 */
data class TrackerState(
    val mode: Mode = Mode.UNKNOWN,
    val inside: Set<String> = emptySet(),
    val registered: Set<String> = emptySet(),
    val nextTickMs: Long? = null,
)

/** A ZONE_ENTER ([entered]) or ZONE_EXIT to sign (spec §8.1). */
data class ZoneEvent(val zoneId: String, val entered: Boolean, val atMs: Long)

/** What goes out besides the events: «В зоне N» without coordinates, or a point (spec §8.1 table). */
sealed interface Report {
    data class InZone(val zoneId: String, val atMs: Long) : Report

    data class Point(val fix: Fix) : Report
}

data class Step(val state: TrackerState, val events: List<ZoneEvent>, val report: Report?)

/**
 * The pure core of spec §8.1: zones and inputs in, the next state, the events to sign and the report out. Proximity
 * alerts decide their own zone; the cadence fixes reconcile every zone (in within the radius, out only beyond radius +
 * accuracy; a fix less precise than [MAX_DECISIVE_ACCURACY_M] decides nothing). The first decision after tracking
 * started is a baseline: no events. Zones that no longer exist are dropped silently (no exit for a deleted zone).
 * Inside several zones the report names the smallest (then the lowest id).
 */
object ZoneTracker {
    const val MAX_DECISIVE_ACCURACY_M = 500

    fun idOf(signature: String): String = signature.substringBefore(':')

    /** A proximity alert of [zoneId]; [fix] is the point to send when this exit leaves every zone. */
    fun proximity(state: TrackerState, zones: List<Zone>, zoneId: String, entering: Boolean, nowMs: Long, fix: Fix?): Step {
        val inside = known(state, zones)
        if (zones.none { it.id == zoneId }) {
            // A stale alert of a deleted zone (the engine unregisters it): nothing to sign, but the deletion may have
            // emptied the zones this phone was in, so the mode follows what is left (Y4 parked minor).
            if (state.mode == Mode.UNKNOWN) return Step(state.copy(inside = inside), emptyList(), null)
            return Step(state.copy(mode = if (inside.isEmpty()) Mode.OUTSIDE else Mode.IN_ZONE, inside = inside), emptyList(), null)
        }
        val events = mutableListOf<ZoneEvent>()
        if (entering && inside.add(zoneId)) events += ZoneEvent(zoneId, entered = true, atMs = nowMs)
        if (!entering && inside.remove(zoneId)) events += ZoneEvent(zoneId, entered = false, atMs = nowMs)
        val mode = if (inside.isEmpty()) Mode.OUTSIDE else Mode.IN_ZONE
        // Spec §8.1: an exit to the outside goes with a point; an entry with the event alone.
        val report = if (mode == Mode.OUTSIDE && events.any { !it.entered }) fix?.let { Report.Point(it) } else null
        return Step(state.copy(mode = mode, inside = inside), if (state.mode == Mode.UNKNOWN) emptyList() else events, report)
    }

    /** A fix of the cadence (the 30-minute check, the 5-minute tick, the first fix); null when none came. */
    fun fix(state: TrackerState, zones: List<Zone>, fix: Fix?, nowMs: Long): Step {
        val inside = known(state, zones)
        if (fix == null || (state.mode == Mode.UNKNOWN && fix.accuracyM > MAX_DECISIVE_ACCURACY_M)) {
            if (state.mode == Mode.UNKNOWN) return Step(state.copy(inside = inside), emptyList(), null)
            val mode = if (inside.isEmpty()) Mode.OUTSIDE else Mode.IN_ZONE
            val report = if (mode == Mode.IN_ZONE) Report.InZone(primary(inside, zones), fix?.timeMs ?: nowMs) else fix?.let { Report.Point(it) }
            return Step(state.copy(mode = mode, inside = inside), emptyList(), report)
        }
        val events = mutableListOf<ZoneEvent>()
        if (fix.accuracyM <= MAX_DECISIVE_ACCURACY_M) {
            for (z in zones.sortedBy { it.id }) {
                val d = Geo.distanceM(fix.latE7, fix.lonE7, z.latE7, z.lonE7)
                if (d <= z.radiusM) {
                    if (inside.add(z.id)) events += ZoneEvent(z.id, entered = true, atMs = fix.timeMs)
                } else if (d > z.radiusM + fix.accuracyM) {
                    if (inside.remove(z.id)) events += ZoneEvent(z.id, entered = false, atMs = fix.timeMs)
                }
            }
        }
        val mode = if (inside.isEmpty()) Mode.OUTSIDE else Mode.IN_ZONE
        val report = if (mode == Mode.IN_ZONE) Report.InZone(primary(inside, zones), fix.timeMs) else Report.Point(fix)
        return Step(state.copy(mode = mode, inside = inside), if (state.mode == Mode.UNKNOWN) emptyList() else events, report)
    }

    private fun known(state: TrackerState, zones: List<Zone>): MutableSet<String> =
        state.inside.filterTo(LinkedHashSet()) { id -> zones.any { it.id == id } }

    private fun primary(inside: Set<String>, zones: List<Zone>): String =
        zones.filter { it.id in inside }.minWith(compareBy<Zone>({ it.radiusM }, { it.id })).id
}
