package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.EntryKind
import java.time.LocalDate

data class SyncPolicy(
    val self: DeviceId,
    val showOthers: Boolean = true,
    val detailRetentionDays: Long = 90,
    val summaryRetentionDays: Long = 365,
    val today: () -> LocalDate = LocalDate::now,
) {
    fun isExpired(e: DecodedEntry): Boolean {
        val date = e.usageDate ?: return false
        val days = when (e.kind) {
            EntryKind.USAGE_DETAIL -> detailRetentionDays
            EntryKind.USAGE_SUMMARY -> summaryRetentionDays
            else -> return false
        }
        return date.isBefore(today().minusDays(days))
    }

    /** With showOthers off, only membership flows in; foreign log entries are not taken. */
    fun acceptsFromPeers(e: DecodedEntry): Boolean = showOthers || e.author == self
}
