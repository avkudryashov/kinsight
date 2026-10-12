package app.kinsight.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/** Writes an empty database exactly as schemas/.../[version].json describes it (tables, indices, Room identity). */
object ExportedSchema {
    fun create(path: String, version: Int, seed: (SQLiteConnection) -> Unit) {
        val json = File("schemas/app.kinsight.core.database.KinsightDatabase/$version.json").readText()
        val connection = BundledSQLiteDriver().open(path)
        try {
            var table: String? = null
            for (line in json.lines().map(String::trim)) {
                Regex("^\"tableName\": \"([a-z_]+)\"").find(line)?.let { table = it.groupValues[1] }
                Regex("^\"createSql\": \"(.*)\",?$").find(line)?.let {
                    connection.execSQL(it.groupValues[1].replace("\${TABLE_NAME}", checkNotNull(table)))
                }
            }
            val setup = json.substringAfter("\"setupQueries\": [").substringBefore("]")
            Regex("\"([^\"]*)\"").findAll(setup).forEach { connection.execSQL(it.groupValues[1]) }
            seed(connection)
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }
}
