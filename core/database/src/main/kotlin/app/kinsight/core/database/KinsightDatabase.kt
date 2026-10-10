package app.kinsight.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** One database per group (one group per device in v1, spec §2). */
@Database(
    entities = [
        EntryRow::class, ContigRow::class, AboveRow::class,
        PendingUsageRow::class, PendingHourRow::class, PendingDayRow::class, PendingEventRow::class,
        CollectorStateRow::class, PublishStateRow::class, AuthorStateRow::class, CatalogStateRow::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class KinsightDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun vectorDao(): VectorDao
    abstract fun usageDao(): UsageDao
    abstract fun authorDao(): AuthorDao
    abstract fun wipeDao(): WipeDao
}

object KinsightDatabaseFactory {
    const val DEFAULT_NAME = "kinsight.db"

    @Volatile
    private var shared: KinsightDatabase? = null

    /** The bundled SQLite keeps the engine identical between the app and the JVM tests. */
    fun open(context: Context, name: String = DEFAULT_NAME): KinsightDatabase =
        Room.databaseBuilder(context.applicationContext, KinsightDatabase::class.java, name)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2)
            .build()

    /** The process-wide database (3a review: one KinsightDatabase per process). Never closed by the app. */
    fun shared(context: Context): KinsightDatabase =
        shared ?: synchronized(this) { shared ?: open(context).also { shared = it } }

    /** Tests only: closes and forgets the shared instance. */
    internal fun resetShared() {
        synchronized(this) {
            shared?.close()
            shared = null
        }
    }
}
