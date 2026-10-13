package app.kinsight.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.kinsight.core.model.DecodedEntry

/**
 * Version 2 (stage 3b) adds tables only: the local usage queue, collector and publisher state, author state and
 * catalog state. Each statement is Room's own createSql template from schemas/.../2.json (pinned by
 * KinsightMigrationTest), so a migrated file validates exactly like a fresh one. No destructive fallback exists:
 * a missing migration must fail loudly rather than wipe the group log.
 */
internal val V2_NEW_TABLES: List<Pair<String, String>> = listOf(
    "pending_usage" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, `package_name` TEXT NOT NULL, `foreground_ms` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`, `package_name`))",
    "pending_hours" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, `screen_ms` INTEGER NOT NULL, `unlocks` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))",
    "pending_days" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`date` TEXT NOT NULL, `zone_id` TEXT NOT NULL, `version` INTEGER NOT NULL, `published_version` INTEGER NOT NULL, `published_hash` TEXT, PRIMARY KEY(`date`))",
    "pending_events" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` INTEGER NOT NULL, `from_ms` INTEGER NOT NULL, `to_ms` INTEGER NOT NULL, `clock_shift_ms` INTEGER NOT NULL)",
    "collector_state" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER NOT NULL, `cursor_ms` INTEGER NOT NULL, `zone_id` TEXT NOT NULL, `screen_on` INTEGER NOT NULL, `resumed` TEXT NOT NULL, `wall_ms` INTEGER NOT NULL, `elapsed_ms` INTEGER NOT NULL, `boot_count` INTEGER NOT NULL, `access_granted` INTEGER, `access_lost_ms` INTEGER, PRIMARY KEY(`id`))",
    "publish_state" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER NOT NULL, `last_usage_publish_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "author_state" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`author` TEXT NOT NULL, `last_seq` INTEGER NOT NULL, `last_lamport` INTEGER NOT NULL, PRIMARY KEY(`author`))",
    "catalog_state" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`package_name` TEXT NOT NULL, `hash` TEXT NOT NULL, PRIMARY KEY(`package_name`))",
)

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        for ((table, sql) in V2_NEW_TABLES) connection.execSQL(sql.replace("\${TABLE_NAME}", table))
    }
}

/** Room's createSql for schema 3's entries table and its indices (pinned against 3.json by KinsightMigrationTest). */
internal const val V3_ENTRIES_TABLE =
    "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`author` TEXT NOT NULL, `seq` INTEGER NOT NULL, `kind` INTEGER NOT NULL, " +
        "`supersede_key` TEXT, `membership` INTEGER NOT NULL, `usage_date` TEXT, `event_kind` INTEGER, `event_to_ms` INTEGER, " +
        "`bytes` BLOB NOT NULL, PRIMARY KEY(`author`, `seq`))"

internal val V3_ENTRIES_INDICES: List<String> = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_entries_supersede_key` ON `\${TABLE_NAME}` (`supersede_key`)",
    "CREATE INDEX IF NOT EXISTS `index_entries_membership` ON `\${TABLE_NAME}` (`membership`)",
    "CREATE INDEX IF NOT EXISTS `index_entries_kind_author_seq` ON `\${TABLE_NAME}` (`kind`, `author`, `seq`)",
)

/**
 * Version 3 (audit A-5): entries gains kind, event_kind and event_to_ms, moves bytes last and gets the (kind, author,
 * seq) index. SQLite cannot reorder columns, so the table is rebuilt; the new columns come from decoding each stored
 * entry once. A row that does not decode (the store never writes one) is kept with kind UNKNOWN.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(V3_ENTRIES_TABLE.replace("\${TABLE_NAME}", "entries_v3"))
        connection.prepare(
            "INSERT INTO entries_v3 (author, seq, kind, supersede_key, membership, usage_date, event_kind, event_to_ms, bytes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            connection.prepare("SELECT author, seq, supersede_key, membership, usage_date, bytes FROM entries").use { rows ->
                while (rows.step()) {
                    val bytes = rows.getBlob(5)
                    val c = EntryColumns.of(runCatching { DecodedEntry.decode(bytes) }.getOrNull())
                    insert.bindText(1, rows.getText(0))
                    insert.bindLong(2, rows.getLong(1))
                    insert.bindLong(3, c.kind.toLong())
                    if (rows.isNull(2)) insert.bindNull(4) else insert.bindText(4, rows.getText(2))
                    insert.bindLong(5, rows.getLong(3))
                    if (rows.isNull(4)) insert.bindNull(6) else insert.bindText(6, rows.getText(4))
                    c.eventKind?.let { insert.bindLong(7, it.toLong()) } ?: insert.bindNull(7)
                    c.eventToMs?.let { insert.bindLong(8, it) } ?: insert.bindNull(8)
                    insert.bindBlob(9, bytes)
                    insert.step()
                    insert.reset()
                }
            }
        }
        connection.execSQL("DROP TABLE entries")
        connection.execSQL("ALTER TABLE entries_v3 RENAME TO entries")
        for (sql in V3_ENTRIES_INDICES) connection.execSQL(sql.replace("\${TABLE_NAME}", "entries"))
    }
}

private class Fix(val author: String, val seq: Long, val kind: Int, val key: String?, val eventKind: Int?, val eventToMs: Long?)

/**
 * Version 4 (protocol 3): no table changes. A 0.8.x build stored what it could not decode as UNKNOWN with no supersede
 * key, and a StateEvent of a kind it did not know with event_kind 0. Each such row is decoded once and gets its kind
 * code, supersede key and event columns (EntryKinds: adding a code needs this pass). Several rows of one author may
 * now share a key: only the highest seq stays, the others are deleted as the store does on supersede.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        val fixes = mutableListOf<Fix>()
        connection.prepare(
            "SELECT author, seq, kind, bytes FROM entries WHERE kind = ${EntryKinds.UNKNOWN} OR " +
                "(kind = ${EntryKinds.STATE_EVENT} AND (event_kind IS NULL OR event_kind = 0))",
        ).use { rows ->
            while (rows.step()) {
                val entry = runCatching { DecodedEntry.decode(rows.getBlob(3)) }.getOrNull() ?: continue
                val c = EntryColumns.of(entry)
                val unknown = rows.getLong(2).toInt() == EntryKinds.UNKNOWN
                if (unknown && c.kind == EntryKinds.UNKNOWN) continue
                fixes += Fix(rows.getText(0), rows.getLong(1), c.kind, if (unknown) entry.supersedeKey else null, c.eventKind, c.eventToMs)
            }
        }
        val newest = fixes.filter { it.key != null }.groupBy { it.key }.mapValues { (_, v) -> v.maxOf { it.seq } }
        connection.prepare("DELETE FROM entries WHERE author = ? AND seq = ?").use { del ->
            for (f in fixes) if (f.key != null && newest[f.key] != f.seq) {
                del.bindText(1, f.author)
                del.bindLong(2, f.seq)
                del.step()
                del.reset()
            }
        }
        connection.prepare(
            "UPDATE entries SET kind = ?, supersede_key = COALESCE(?, supersede_key), event_kind = ?, event_to_ms = ? " +
                "WHERE author = ? AND seq = ?",
        ).use { st ->
            for (f in fixes) {
                if (f.key != null && newest[f.key] != f.seq) continue
                st.bindLong(1, f.kind.toLong())
                f.key?.let { st.bindText(2, it) } ?: st.bindNull(2)
                f.eventKind?.let { st.bindLong(3, it.toLong()) } ?: st.bindNull(3)
                f.eventToMs?.let { st.bindLong(4, it) } ?: st.bindNull(4)
                st.bindText(5, f.author)
                st.bindLong(6, f.seq)
                st.step()
                st.reset()
            }
        }
    }
}
