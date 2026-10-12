package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.CategoryOverride
import app.kinsight.core.model.proto.UsageDetail
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate

val TODAY: LocalDate = LocalDate.of(2026, 10, 9)

fun EntryAuthor.summary(date: String, totalMs: Long): DecodedEntry = create {
    it.copy(usage_summary = UsageSummary(local_date = date, zone_id = "Europe/Madrid", total_ms = totalMs))
}

fun EntryAuthor.detail(date: String): DecodedEntry = create {
    it.copy(usage_detail = UsageDetail(local_date = date, zone_id = "Europe/Madrid"))
}

/** [category] is the wire value: AppCategory ordinal + 1, 0 = cleared; [rank] orders it before Lamport (spec §4.4). */
fun EntryAuthor.categoryOverride(packageName: String, category: Int, rank: Long = 0): DecodedEntry = create {
    it.copy(category_override = CategoryOverride(package_name = packageName, category = category, rank = rank))
}
