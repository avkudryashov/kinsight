package app.kinsight.core.blocking

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.TodayUsage
import app.kinsight.core.usage.EventSource
import app.kinsight.core.usage.EventType
import app.kinsight.core.usage.LauncherSource
import app.kinsight.core.usage.UsageAccess
import app.kinsight.core.usage.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodayUsageReaderTest {
    private val zone = ZoneId.of("UTC")
    private val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun reader(events: List<UsageEvent>, granted: Boolean = true) = TodayUsageReader(
        source = object : EventSource {
            override fun read(fromMs: Long, untilMs: Long) = events.filter { it.timeMs in fromMs until untilMs }
        },
        access = object : UsageAccess {
            override fun granted() = granted
        },
        launchers = object : LauncherSource {
            override fun launchers() = setOf(LAUNCHER)
        },
        zone = { zone },
        categoryOf = { if (it == GAME) AppCategory.GAMES else AppCategory.OTHER },
    )

    @Test
    fun countsOnlyTodayIncludingAnAppOpenSinceYesterday() {
        val events = listOf(
            UsageEvent(midnight - 10 * min, EventType.RESUMED, GAME, "A"),
            UsageEvent(midnight + 20 * min, EventType.PAUSED, GAME, "A"),
            UsageEvent(midnight + 20 * min, EventType.RESUMED, LAUNCHER, "L"),
            UsageEvent(midnight + 30 * min, EventType.PAUSED, LAUNCHER, "L"),
            UsageEvent(midnight + 30 * min, EventType.RESUMED, CHAT, "C"),
        )
        val today = reader(events).read(midnight + 35 * min)
        assertEquals(
            DatedUsage(
                LocalDate.of(2026, 10, 24),
                TodayUsage(25 * min, mapOf(AppCategory.GAMES to 20 * min, AppCategory.OTHER to 5 * min), mapOf(GAME to 20 * min, CHAT to 5 * min)),
            ),
            today,
        )
    }

    @Test
    fun noUsageAccessIsNoKnownUsage() {
        assertNull(reader(emptyList(), granted = false).read(midnight + min))
    }

    private companion object {
        const val GAME = "com.example.game"
        const val CHAT = "com.example.chat"
        const val LAUNCHER = "com.example.launcher"
    }
}
