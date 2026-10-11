package app.kinsight.core.usage

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.database.PendingHourRow
import app.kinsight.core.database.PendingUsageRow
import app.kinsight.core.model.WireConstants
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary

class DayContent(val summary: UsageSummary, val detail: UsageDetail, val hash: String)

/** Pure: one local date of the queue → UsageSummary + UsageDetail (variant A: hourly per-app) and a content hash. */
object UsageContent {
    /** At most this many named apps per UsageDetail hour; the rest of the hour is folded into [OTHER_PACKAGE]. */
    const val MAX_APPS_PER_HOUR = 40

    /** Reserved package name for the folded remainder of a crowded hour ([WireConstants.REMAINDER_PACKAGE]). */
    const val OTHER_PACKAGE = WireConstants.REMAINDER_PACKAGE

    fun build(date: String, zoneId: String, apps: List<PendingUsageRow>, hours: List<PendingHourRow>): DayContent {
        val live = apps.filter { it.foregroundMs > 0 }
        val totals = live.groupBy { it.packageName }
            .map { (pkg, rows) -> AppTotal(package_name = pkg, foreground_ms = rows.sumOf { it.foregroundMs }) }
            .sortedWith(compareByDescending<AppTotal> { it.foreground_ms }.thenBy { it.package_name })
        val summary = UsageSummary(
            local_date = date,
            zone_id = zoneId,
            total_ms = hours.sumOf { it.screenMs },
            apps = totals,
            unlocks = hours.sumOf { it.unlocks },
        )
        val appsByHour = live.groupBy { it.hour }
        val hourRows = hours.associateBy { it.hour }
        val activeHours = (appsByHour.keys + hours.filter { it.screenMs > 0 || it.unlocks > 0 }.map { it.hour }).sorted()
        val detail = UsageDetail(
            local_date = date,
            zone_id = zoneId,
            hours = activeHours.map { h ->
                HourBucket(
                    hour = h,
                    apps = cap(appsByHour[h].orEmpty()),
                    unlocks = hourRows[h]?.unlocks ?: 0,
                )
            },
        )
        val hash = Primitives.sha256(UsageSummary.ADAPTER.encode(summary), UsageDetail.ADAPTER.encode(detail)).toHex()
        return DayContent(summary, detail, hash)
    }

    /**
     * One hour's cells: the [MAX_APPS_PER_HOUR] apps with the most time (ties by package name), by package name,
     * then one [OTHER_PACKAGE] cell with the exact remainder of the hour if anything was left out.
     */
    private fun cap(rows: List<PendingUsageRow>): List<AppTotal> {
        val ranked = rows.sortedWith(compareByDescending<PendingUsageRow> { it.foregroundMs }.thenBy { it.packageName })
        val kept = ranked.take(MAX_APPS_PER_HOUR).sortedBy { it.packageName }
            .map { AppTotal(package_name = it.packageName, foreground_ms = it.foregroundMs) }
        val rest = ranked.drop(MAX_APPS_PER_HOUR)
        return if (rest.isEmpty()) kept else kept + AppTotal(package_name = OTHER_PACKAGE, foreground_ms = rest.sumOf { it.foregroundMs })
    }
}
