package app.kinsight.core.usage

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.database.PendingHourRow
import app.kinsight.core.database.PendingUsageRow
import app.kinsight.core.model.proto.AppTotal
import app.kinsight.core.model.proto.HourBucket
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary

class DayContent(val summary: UsageSummary, val detail: UsageDetail, val hash: String)

/** Pure: one local date of the queue → UsageSummary + UsageDetail (variant A: hourly per-app) and a content hash. */
object UsageContent {
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
                    apps = appsByHour[h].orEmpty().sortedBy { it.packageName }
                        .map { AppTotal(package_name = it.packageName, foreground_ms = it.foregroundMs) },
                    unlocks = hourRows[h]?.unlocks ?: 0,
                )
            },
        )
        val hash = Primitives.sha256(UsageSummary.ADAPTER.encode(summary), UsageDetail.ADAPTER.encode(detail)).toHex()
        return DayContent(summary, detail, hash)
    }
}
