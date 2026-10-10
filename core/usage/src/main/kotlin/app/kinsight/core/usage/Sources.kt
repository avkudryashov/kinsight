package app.kinsight.core.usage

import java.time.ZoneId

/** UsageStatsManager.queryEvents behind an interface: events with timeMs in [fromMs, untilMs). */
interface EventSource {
    fun read(fromMs: Long, untilMs: Long): List<UsageEvent>
}

/** Whether the app may read usage history (AppOpsManager GET_USAGE_STATS). */
interface UsageAccess {
    fun granted(): Boolean
}

interface DeviceClock {
    fun sample(): ClockSample
    fun zone(): ZoneId
}

/** Packages excluded from screen time: the default launcher (spec §7). */
interface LauncherSource {
    fun launchers(): Set<String>
}
