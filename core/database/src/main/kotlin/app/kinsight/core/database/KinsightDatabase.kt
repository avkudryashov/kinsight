package app.kinsight.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** One database per group (one group per device in v1, spec §2). */
@Database(entities = [EntryRow::class, ContigRow::class, AboveRow::class], version = 1, exportSchema = true)
abstract class KinsightDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun vectorDao(): VectorDao
}

object KinsightDatabaseFactory {
    const val DEFAULT_NAME = "kinsight.db"

    /** The bundled SQLite keeps the engine identical between the app and the JVM tests. */
    fun open(context: Context, name: String = DEFAULT_NAME): KinsightDatabase =
        Room.databaseBuilder(context.applicationContext, KinsightDatabase::class.java, name)
            .setDriver(BundledSQLiteDriver())
            .build()
}
