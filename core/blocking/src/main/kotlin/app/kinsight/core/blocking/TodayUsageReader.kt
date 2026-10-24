package app.kinsight.core.blocking

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.TodayUsage
import app.kinsight.core.usage.Aggregator
import app.kinsight.core.usage.EventSource
import app.kinsight.core.usage.LauncherSource
import app.kinsight.core.usage.StreamState
import app.kinsight.core.usage.UsageAccess
import java.time.Instant
import java.time.ZoneId

/**
 * The child's today so far for the limits (spec §5.2), straight from the system's event stream through the same
 * [Aggregator] the collector uses (launchers excluded): the screen time, each app's time and their stack by the effective
 * [categoryOf]. The day is the phone's local date. The read starts [LOOKBACK_MS] before midnight, so an app open across
 * midnight counts from 00:00; only today's hours are kept. Null without usage access: nothing is known, so limits do not
 * block (explicit blocks still do).
 */
class TodayUsageReader(
    private val source: EventSource,
    private val access: UsageAccess,
    private val launchers: LauncherSource,
    private val zone: () -> ZoneId,
    private val categoryOf: (String) -> AppCategory,
) {
    fun read(nowMs: Long): TodayUsage? {
        if (!access.granted()) return null
        val z = zone()
        val day = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        val from = day.atStartOfDay(z).toInstant().toEpochMilli() - LOOKBACK_MS
        val events = source.read(from, nowMs)
        // Without access the system answers with no events (Collector): an idle day must not stand in for unknown.
        if (!access.granted()) return null
        val agg = Aggregator.run(StreamState.INITIAL, events, from, nowMs, z, launchers.launchers())
        val byApp = agg.appMs.filterKeys { it.slot.date == day }.entries
            .groupingBy { it.key.packageName }.fold(0L) { sum, e -> sum + e.value }
        val totalMs = agg.screenMs.filterKeys { it.date == day }.values.sum()
        val byCategory = byApp.entries.groupingBy { categoryOf(it.key) }.fold(0L) { sum, e -> sum + e.value }
        return TodayUsage(totalMs, byCategory, byApp)
    }

    companion object {
        const val LOOKBACK_MS = 3_600_000L
    }
}
