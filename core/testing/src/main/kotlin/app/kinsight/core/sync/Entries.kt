package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
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
