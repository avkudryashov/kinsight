package app.kinsight.core.usage

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class UsageVsSystemDeviceTest {
    private val context = grantUsageAccess()
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    @Test
    fun yesterdayMatchesTheSystemDailyTotal(): Unit {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yEnd = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val launchers = HomeLauncherSource(context.packageManager).launchers()

        // System daily buckets need not start at local midnight: compare on the bucket that covers yesterday most.
        // A bucket is keyed by its start only: in the bucket still in progress every package carries its own
        // lastTimeStamp (its latest event), so the bucket ends at the largest one. A closed bucket (one shared
        // lastTimeStamp) is preferred: the one in progress mostly holds today's use.
        val buckets = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, yStart, yEnd)
            .groupBy { it.firstTimeStamp }
            .map { (first, stats) -> Bucket(first, stats.maxOf { it.lastTimeStamp }, stats) }
            .filter { it.overlap(yStart, yEnd) > 0 }
        val bucket = assertNotNull(
            buckets.filter { it.closed }.maxByOrNull { it.overlap(yStart, yEnd) }
                ?: buckets.maxByOrNull { it.overlap(yStart, yEnd) },
            "no daily bucket overlaps yesterday",
        )
        val systemByApp = bucket.stats.filter { it.packageName !in launchers }
            .groupBy { it.packageName }.mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } }
        val system = systemByApp.values.sum()
        val events = UsageStatsEventSource(usm).read(bucket.first, bucket.last)
        val oursByApp = Aggregator.run(StreamState.INITIAL, events, bucket.first, bucket.last, zone, launchers).appMs
            .entries.groupBy({ it.key.packageName }, { it.value }).mapValues { (_, ms) -> ms.sum() }
        val ours = oursByApp.values.sum()
        val tolerance = maxOf(system / 20, 60_000L)
        val report = "bucket=${bucket.first}..${bucket.last} kind=${if (bucket.closed) "closed" else "in-progress"} " +
            "overlap=${bucket.overlap(yStart, yEnd) / 60_000L}min system=${system / 1000}s ours=${ours / 1000}s " +
            "diff=${(ours - system) / 1000}s tolerance=${tolerance / 1000}s launchers=$launchers"
        Log.i(TAG, report)
        (systemByApp.keys + oursByApp.keys)
            .map { it to (oursByApp[it] ?: 0L) - (systemByApp[it] ?: 0L) }
            .filter { abs(it.second) >= 10_000L }
            .sortedByDescending { abs(it.second) }
            .forEach { (pkg, d) ->
                val sys = (systemByApp[pkg] ?: 0L) / 1000
                Log.i(TAG, "  $pkg system=${sys}s ours=${(oursByApp[pkg] ?: 0L) / 1000}s diff=${d / 1000}s")
            }

        assumeTrue("too little usage yesterday for a relative check: $report", system >= 10 * 60_000L)
        assertTrue(abs(ours - system) <= tolerance, report)
    }

    @Test
    fun reportsTheOldestAvailableEvent(): Unit {
        val now = System.currentTimeMillis()
        val events = UsageStatsEventSource(usm).read(now - 30 * 86_400_000L, now)
        val oldest = assertNotNull(events.minOfOrNull { it.timeMs }, "no events in 30 days")
        Log.i(TAG, "oldest event ${(now - oldest) / 3_600_000L} h ago (${(now - oldest) / 86_400_000.0} days)")
        // The collector trusts 8 days of history (Collector.HISTORY_MS); the phone must keep at least that.
        assertTrue(now - oldest >= Collector.HISTORY_MS, "history shorter than Collector.HISTORY_MS")
    }

    private class Bucket(val first: Long, val last: Long, val stats: List<UsageStats>) {
        /** Closed: the system rolled the bucket over and stamped one end time on every package. */
        val closed = stats.all { it.lastTimeStamp == last }

        fun overlap(from: Long, until: Long): Long = minOf(last, until) - maxOf(first, from)
    }

    private companion object {
        const val TAG = "KinsightUsageCheck"
    }
}
