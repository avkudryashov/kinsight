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

/**
 * Version 4 (protocol 3): no table changes. Rows a 0.8.x build stored as UNKNOWN may be CloudAccess, Location or SafeZone
 * entries; each is decoded once and gets its code (EntryKinds: adding a code needs this pass).
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        val updates = mutableListOf<Triple<String, Long, Int>>()
        connection.prepare("SELECT author, seq, bytes FROM entries WHERE kind = ${EntryKinds.UNKNOWN}").use { rows ->
            while (rows.step()) {
                val kind = EntryColumns.of(runCatching { DecodedEntry.decode(rows.getBlob(2)) }.getOrNull()).kind
                if (kind != EntryKinds.UNKNOWN) updates += Triple(rows.getText(0), rows.getLong(1), kind)
            }
        }
        connection.prepare("UPDATE entries SET kind = ? WHERE author = ? AND seq = ?").use { st ->
            for ((author, seq, kind) in updates) {
                st.bindLong(1, kind.toLong())
                st.bindText(2, author)
                st.bindLong(3, seq)
                st.step()
                st.reset()
            }
        }
    }
}
