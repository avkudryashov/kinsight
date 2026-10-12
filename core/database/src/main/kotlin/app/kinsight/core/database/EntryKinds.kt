package app.kinsight.core.database

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryKind

/**
 * Stored codes of entries.kind (schema 3). Persisted in every database: never renumber, only add.
 *
 * Entries from newer peers whose payload this build does not know are stored with [UNKNOWN] (0) and keep it: insert
 * never revisits a row. Adding a code therefore needs a migration that re-decodes the rows with kind 0 and sets
 * their new code, or [EntryDao.bytesOfKind] will miss the entries that arrived before the update.
 */
object EntryKinds {
    const val UNKNOWN = 0
    const val USAGE_SUMMARY = 1
    const val USAGE_DETAIL = 2
    const val APP_CATALOG = 3
    const val STATE_EVENT = 4
    const val HEARTBEAT = 5
    const val MEMBERSHIP_OP = 6
    const val CATEGORY_OVERRIDE = 7

    fun code(kind: EntryKind): Int = when (kind) {
        EntryKind.USAGE_SUMMARY -> USAGE_SUMMARY
        EntryKind.USAGE_DETAIL -> USAGE_DETAIL
        EntryKind.APP_CATALOG -> APP_CATALOG
        EntryKind.STATE_EVENT -> STATE_EVENT
        EntryKind.HEARTBEAT -> HEARTBEAT
        EntryKind.MEMBERSHIP_OP -> MEMBERSHIP_OP
        EntryKind.CATEGORY_OVERRIDE -> CATEGORY_OVERRIDE
        EntryKind.UNKNOWN -> UNKNOWN
    }
}

/** The derived columns of one entry row; null (bytes the store cannot decode) is UNKNOWN without event fields. */
internal class EntryColumns(val kind: Int, val eventKind: Int?, val eventToMs: Long?) {
    companion object {
        fun of(e: DecodedEntry?): EntryColumns {
            if (e == null) return EntryColumns(EntryKinds.UNKNOWN, null, null)
            val event = e.body.state_event
            return EntryColumns(EntryKinds.code(e.kind), event?.kind?.value, event?.to_ms)
        }
    }
}
