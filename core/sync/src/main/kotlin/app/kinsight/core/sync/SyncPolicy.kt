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

    /**
     * With showOthers off (a child), only membership and the kinds every member needs from the parents flow in
     * ([FROM_PARENTS_TO_ALL]); the parents' other entries are not taken.
     */
    fun acceptsFromPeers(e: DecodedEntry): Boolean = showOthers || e.author == self || e.kind in FROM_PARENTS_TO_ALL

    companion object {
        /** Parent-signed kinds a child takes as well (R5 of the cloud plan): storage access now, safe zones in Y4. */
        val FROM_PARENTS_TO_ALL: Set<EntryKind> = setOf(EntryKind.CLOUD_ACCESS)
    }
}
