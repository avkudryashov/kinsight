package app.kinsight.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/** The version 1 schema exactly as exported to schemas/.../1.json (Room templates with ${TABLE_NAME}), frozen. */
object V1Schema {
    const val IDENTITY_HASH = "4ab30b45bbf5a66b2ff5082fd3c61197"

    val TABLES: List<Pair<String, String>> = listOf(
        "entries" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`author` TEXT NOT NULL, `seq` INTEGER NOT NULL, `bytes` BLOB NOT NULL, `supersede_key` TEXT, `membership` INTEGER NOT NULL, `usage_date` TEXT, PRIMARY KEY(`author`, `seq`))",
        "entries" to "CREATE UNIQUE INDEX IF NOT EXISTS `index_entries_supersede_key` ON `\${TABLE_NAME}` (`supersede_key`)",
        "entries" to "CREATE INDEX IF NOT EXISTS `index_entries_membership` ON `\${TABLE_NAME}` (`membership`)",
        "vector_contig" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`author` TEXT NOT NULL, `contig` INTEGER NOT NULL, PRIMARY KEY(`author`))",
        "vector_above" to "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`author` TEXT NOT NULL, `seq` INTEGER NOT NULL, PRIMARY KEY(`author`, `seq`))",
    )

    /** Writes a version 1 database file the way Room 3a left it, then lets [seed] add rows. */
    fun create(path: String, seed: (SQLiteConnection) -> Unit) {
        val connection = BundledSQLiteDriver().open(path)
        try {
            for ((table, sql) in TABLES) connection.execSQL(sql.replace("\${TABLE_NAME}", table))
            connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$IDENTITY_HASH')")
            seed(connection)
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }
}
