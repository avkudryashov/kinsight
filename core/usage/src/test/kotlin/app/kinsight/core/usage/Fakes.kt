package app.kinsight.core.usage

import java.time.ZoneId

class FakeSource : EventSource {
    val events = mutableListOf<UsageEvent>()
    val reads = mutableListOf<Pair<Long, Long>>()

    override fun read(fromMs: Long, untilMs: Long): List<UsageEvent> {
        reads += fromMs to untilMs
        return events.filter { it.timeMs in fromMs until untilMs }
    }
}

class FakeAccess(var granted: Boolean = true) : UsageAccess {
    override fun granted() = granted
}

class FakeClock(
    var wallMs: Long,
    var currentZone: ZoneId,
    var elapsedMs: Long = 1_000_000_000L,
    var bootCount: Int = 1,
) : DeviceClock {
    override fun sample() = ClockSample(wallMs, elapsedMs, bootCount)
    override fun zone() = currentZone

    /** Real time passes: both clocks move. */
    fun advance(ms: Long) {
        wallMs += ms
        elapsedMs += ms
    }
}

class FakeLaunchers(var packages: Set<String> = setOf(LAUNCHER)) : LauncherSource {
    override fun launchers() = packages
}
