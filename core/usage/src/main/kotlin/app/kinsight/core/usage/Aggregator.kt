package app.kinsight.core.usage

import java.time.ZoneId

/** One resumed activity; two instances of the same class are merged (the event stream carries no instance id). */
data class ActivityKey(val packageName: String, val className: String)

/** What the event stream says at a moment: is the screen interactive, which activities are resumed. */
data class StreamState(val screenOn: Boolean, val resumed: Set<ActivityKey>) {
    companion object {
        /** Before any event: an interactive screen (RESUMED implies one) and nothing open. */
        val INITIAL = StreamState(screenOn = true, resumed = emptySet())
    }
}

data class AppSlot(val slot: HourSlot, val packageName: String)

data class Aggregation(
    val appMs: Map<AppSlot, Long>,
    val screenMs: Map<HourSlot, Long>,
    val unlocks: Map<HourSlot, Int>,
    val end: StreamState,
)

/**
 * Pure: events → foreground time per app and local hour, screen time (union) per hour, unlocks per hour (spec §7).
 * Running [fromMs, cut) and then [cut, untilMs) from the first run's [Aggregation.end] gives the same totals as one
 * run over [fromMs, untilMs).
 */
object Aggregator {
    fun run(
        start: StreamState,
        events: List<UsageEvent>,
        fromMs: Long,
        untilMs: Long,
        zone: ZoneId,
        excluded: Set<String>,
    ): Aggregation {
        val appMs = HashMap<AppSlot, Long>()
        val screenMs = HashMap<HourSlot, Long>()
        val unlocks = HashMap<HourSlot, Int>()
        var screenOn = start.screenOn
        val resumed = LinkedHashSet(start.resumed)

        fun credit(a: Long, b: Long) {
            if (b <= a || !screenOn) return
            val apps = resumed.mapTo(HashSet()) { it.packageName } - excluded
            if (apps.isEmpty()) return
            LocalHours.split(a, b, zone) { slot, ms ->
                for (p in apps) appMs.merge(AppSlot(slot, p), ms, Long::plus)
                screenMs.merge(slot, ms, Long::plus)
            }
        }

        var t = fromMs
        for (e in events.filter { it.timeMs in fromMs until untilMs }.sortedBy { it.timeMs }) {
            credit(t, e.timeMs)
            t = e.timeMs
            when (e.type) {
                EventType.RESUMED -> resumed += ActivityKey(e.packageName, e.className)
                EventType.PAUSED -> resumed -= ActivityKey(e.packageName, e.className)
                EventType.SCREEN_ON -> screenOn = true
                EventType.SCREEN_OFF -> {
                    screenOn = false
                    resumed.clear()
                }
                EventType.UNLOCK -> unlocks.merge(LocalHours.slot(e.timeMs, zone), 1, Int::plus)
                EventType.SHUTDOWN -> {
                    screenOn = false
                    resumed.clear()
                }
                EventType.STARTUP -> {
                    screenOn = true
                    resumed.clear()
                }
            }
        }
        credit(t, untilMs)
        return Aggregation(appMs, screenMs, unlocks, StreamState(screenOn, resumed.toSet()))
    }
}
