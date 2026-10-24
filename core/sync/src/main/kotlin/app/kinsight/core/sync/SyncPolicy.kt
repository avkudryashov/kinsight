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
    /**
     * The parent-signed kinds a member with [showOthers] off takes, and the kinds a session sends to a child. Always
     * [FROM_PARENTS_TO_ALL] in the app; a test narrows it to play an older build.
     */
    val shareKinds: Set<EntryKind> = FROM_PARENTS_TO_ALL,
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
     * ([shareKinds]); the parents' other entries are not taken.
     */
    fun acceptsFromPeers(e: DecodedEntry): Boolean = showOthers || e.author == self || e.kind in shareKinds

    companion object {
        /** Spec §8: LOCATION entries older than the current day plus a margin, 48 h, go at prune and on receipt. */
        const val LOCATION_RETENTION_MS = 48L * 60 * 60 * 1000

        /**
         * Parent-signed kinds a child takes as well (R5 of the cloud plan): storage access, the safe zones it watches (spec
         * §8.1), the parents' PIN it checks (spec §10), the app rules it enforces (spec §4: a rule must reach the child
         * that applies it) and the category overrides (so the child and the parents compute the same category for a
         * category rule).
         *
         * Invariant for widening it (a new kind, a kind older builds already know such as HEARTBEAT, or a share rule that
         * accepts more): a child on an older build drops such an entry (Unwanted, as UNKNOWN or as a known kind outside
         * its set, or Skipped), and SyncSession keeps the author's log open below any entry of the author's own log it
         * dropped (SyncSession.holdsBack), instead of completing over it, so the child takes it after its update. This
         * holds only because a session sends a child nothing but the sender's share set: never send a child other kinds.
         */
        val FROM_PARENTS_TO_ALL: Set<EntryKind> = setOf(
            EntryKind.CLOUD_ACCESS, EntryKind.SAFE_ZONE, EntryKind.PARENT_PIN, EntryKind.APP_RULE, EntryKind.CATEGORY_OVERRIDE,
        )
    }
}
