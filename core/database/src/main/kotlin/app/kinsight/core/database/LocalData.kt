package app.kinsight.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Every table of schema 5, in WipeDao order; LocalDataTest pins it against the exported schema. */
val WIPED_TABLES: List<String> = listOf(
    "entries", "vector_contig", "vector_above", "pending_usage", "pending_hours", "pending_days", "pending_events",
    "collector_state", "publish_state", "author_state", "catalog_state", "cloud_cursor",
)

/**
 * Empties every table in one transaction: leaving a group, a new identity, joining a group (spec §4.6, one group per
 * database). The open database and the process singletons stay usable. Run under the usage pipeline lock.
 */
suspend fun KinsightDatabase.wipeAll(): Unit = write {
    with(wipeDao()) {
        entries()
        vectorContig()
        vectorAbove()
        pendingUsage()
        pendingHours()
        pendingDays()
        pendingEvents()
        collectorState()
        publishState()
        authorState()
        catalogState()
        cloudCursor()
    }
}

/** One value now, then one after every committed change of the entry log (Room invalidation). */
fun KinsightDatabase.entriesChanged(): Flow<Unit> = invalidationTracker.createFlow("entries").map { }
