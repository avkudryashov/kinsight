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
    /** The receiver's clock for LOCATION expiry (spec §8: today plus a margin, 48 h). */
    val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun isExpired(e: DecodedEntry): Boolean {
        e.body.location?.let { l ->
            // The point's time is Location.fix_time_ms (spec §8). A time more than the retention ahead is garbage: it goes too.
            val now = nowMs()
            return l.fix_time_ms < now - LOCATION_RETENTION_MS || l.fix_time_ms > now + LOCATION_RETENTION_MS
        }
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
        /** Spec §8: LOCATION entries older than the current day plus a margin, 48 h, go at prune and on receipt. */
        const val LOCATION_RETENTION_MS = 48L * 60 * 60 * 1000

        /** Parent-signed kinds a child takes as well (R5 of the cloud plan): storage access, and the safe zones it watches (spec §8.1). */
        val FROM_PARENTS_TO_ALL: Set<EntryKind> = setOf(EntryKind.CLOUD_ACCESS, EntryKind.SAFE_ZONE)
    }
}
