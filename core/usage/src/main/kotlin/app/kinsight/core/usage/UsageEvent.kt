package app.kinsight.core.usage

/** The UsageStatsManager events the pipeline reads (spec §7), in platform-free form. */
enum class EventType { RESUMED, PAUSED, SCREEN_ON, SCREEN_OFF, UNLOCK, SHUTDOWN, STARTUP }

data class UsageEvent(
    val timeMs: Long,
    val type: EventType,
    val packageName: String = "",
    val className: String = "",
)
