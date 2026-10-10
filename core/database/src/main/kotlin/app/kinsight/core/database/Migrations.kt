package app.kinsight.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
