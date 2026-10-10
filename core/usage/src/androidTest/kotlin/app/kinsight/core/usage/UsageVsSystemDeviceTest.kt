package app.kinsight.core.usage

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
        // lastTimeStamp (its latest event), so the bucket ends at the largest one.
        val buckets = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, yStart, yEnd)
            .groupBy { it.firstTimeStamp }
            .entries.associate { (first, stats) -> (first to stats.maxOf { it.lastTimeStamp }) to stats }
        val (range, stats) = assertNotNull(
            buckets.entries.maxByOrNull { (r, _) -> minOf(r.second, yEnd) - maxOf(r.first, yStart) },
            "no daily buckets",
        )
        val system = stats.filter { it.packageName !in launchers }.sumOf { it.totalTimeInForeground }
        val events = UsageStatsEventSource(usm).read(range.first, range.second)
        val ours = Aggregator.run(StreamState.INITIAL, events, range.first, range.second, zone, launchers).appMs.values.sum()
        val report = "bucket=${range.first}..${range.second} system=${system / 1000}s ours=${ours / 1000}s " +
            "diff=${(ours - system) / 1000}s launchers=$launchers"
        Log.i(TAG, report)

        assumeTrue("too little usage yesterday for a relative check: $report", system >= 10 * 60_000L)
        val tolerance = maxOf(system / 20, 60_000L)
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

    private companion object {
        const val TAG = "KinsightUsageCheck"
    }
}
