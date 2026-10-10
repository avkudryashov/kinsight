package app.kinsight.core.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/** EventReader (spec §7): UsageStatsManager.queryEvents → [UsageEvent], only the types the pipeline uses. */
class UsageStatsEventSource(private val usm: UsageStatsManager) : EventSource {
    override fun read(fromMs: Long, untilMs: Long): List<UsageEvent> {
        val out = ArrayList<UsageEvent>()
        val events = usm.queryEvents(fromMs, untilMs) ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            val type = typeOf(e.eventType) ?: continue
            out += UsageEvent(e.timeStamp, type, e.packageName.orEmpty(), e.className.orEmpty())
        }
        return out
    }

    companion object {
        fun typeOf(eventType: Int): EventType? = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> EventType.RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> EventType.PAUSED
            UsageEvents.Event.SCREEN_INTERACTIVE -> EventType.SCREEN_ON
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventType.SCREEN_OFF
            UsageEvents.Event.KEYGUARD_HIDDEN -> EventType.UNLOCK
            UsageEvents.Event.DEVICE_SHUTDOWN -> EventType.SHUTDOWN
            UsageEvents.Event.DEVICE_STARTUP -> EventType.STARTUP
            else -> null
        }
    }
}
