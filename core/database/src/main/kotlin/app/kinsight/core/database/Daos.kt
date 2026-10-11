package app.kinsight.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE author = :author AND seq = :seq")
    suspend fun get(author: String, seq: Long): EntryRow?

    @Query("SELECT * FROM entries WHERE supersede_key = :key")
    suspend fun bySupersedeKey(key: String): EntryRow?

    @Insert
    suspend fun insert(row: EntryRow)

    @Query("UPDATE entries SET bytes = :bytes WHERE author = :author AND seq = :seq")
    suspend fun replaceBytes(author: String, seq: Long, bytes: ByteArray)

    @Query("DELETE FROM entries WHERE author = :author AND seq = :seq")
    suspend fun delete(author: String, seq: Long)

    @Query("SELECT bytes FROM entries WHERE membership = 1 ORDER BY author, seq")
    suspend fun membershipBytes(): List<ByteArray>

    @Query("SELECT bytes FROM entries WHERE author = :author AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun bytesAfter(author: String, afterSeq: Long, limit: Int): List<ByteArray>

    @Query("SELECT bytes FROM entries ORDER BY author, seq")
    suspend fun allBytes(): List<ByteArray>

    @Query("SELECT * FROM entries ORDER BY author, seq")
    suspend fun all(): List<EntryRow>

    @Query("SELECT bytes FROM entries WHERE usage_date IS NOT NULL")
    suspend fun bytesWithUsageDate(): List<ByteArray>

    /** Every entry of one [EntryKinds] code, by author and seq: served by the (kind, author, seq) index, no sort. */
    @Query("SELECT bytes FROM entries WHERE kind = :kind ORDER BY author, seq")
    suspend fun bytesOfKind(kind: Int): List<ByteArray>

    /** Entries whose supersede key lies in [from, to] (inclusive, by the unique key index), e.g. one author's details of a date range. */
    @Query("SELECT bytes FROM entries WHERE supersede_key BETWEEN :from AND :to ORDER BY supersede_key")
    suspend fun bytesBySupersedeKeyRange(from: String, to: String): List<ByteArray>

    @Query("SELECT MAX(seq) FROM entries WHERE author = :author")
    suspend fun maxSeq(author: String): Long?
}

@Dao
interface VectorDao {
    @Query("SELECT * FROM vector_contig WHERE contig > 0")
    suspend fun positive(): List<ContigRow>

    @Query("SELECT contig FROM vector_contig WHERE author = :author")
    suspend fun contig(author: String): Long?

    /**
     * REPLACE rather than @Upsert: Room's upsert catches the conflict as android.database.SQLException and checks
     * its message, but the mockable android.jar drops the message in JVM tests, so the upsert rethrows the
     * exception instead of falling back to UPDATE. vector_contig has one primary key and no foreign keys or triggers.
     *
     * REPLACE deletes the conflicting row and inserts a new one, so any column added to [ContigRow] later is reset
     * to its default on every call unless the caller includes it in the row passed here.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setContig(row: ContigRow)

    /** One statement, so a concurrent write cannot slip between the two lookups. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM vector_contig WHERE author = :author AND contig >= :seq) " +
            "OR EXISTS(SELECT 1 FROM vector_above WHERE author = :author AND seq = :seq)",
    )
    suspend fun isAccounted(author: String, seq: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAbove(row: AboveRow)

    @Query("SELECT seq FROM vector_above WHERE author = :author ORDER BY seq")
    suspend fun above(author: String): List<Long>

    @Query("DELETE FROM vector_above WHERE author = :author AND seq <= :upTo")
    suspend fun deleteAboveUpTo(author: String, upTo: Long)
}

@Dao
interface UsageDao {
    @Query("INSERT OR IGNORE INTO pending_usage(date, hour, package_name, foreground_ms) VALUES (:date, :hour, :packageName, 0)")
    suspend fun ensureUsage(date: String, hour: Int, packageName: String)

    @Query(
        "UPDATE pending_usage SET foreground_ms = MIN(foreground_ms + :ms, :capMs) " +
            "WHERE date = :date AND hour = :hour AND package_name = :packageName",
    )
    suspend fun addUsage(date: String, hour: Int, packageName: String, ms: Long, capMs: Long)

    @Query("INSERT OR IGNORE INTO pending_hours(date, hour, screen_ms, unlocks) VALUES (:date, :hour, 0, 0)")
    suspend fun ensureHour(date: String, hour: Int)

    @Query(
        "UPDATE pending_hours SET screen_ms = MIN(screen_ms + :ms, :capMs), unlocks = unlocks + :unlocks " +
            "WHERE date = :date AND hour = :hour",
    )
    suspend fun addHour(date: String, hour: Int, ms: Long, unlocks: Int, capMs: Long)

    @Query(
        "INSERT OR IGNORE INTO pending_days(date, zone_id, version, published_version, published_hash) " +
            "VALUES (:date, :zoneId, 0, 0, NULL)",
    )
    suspend fun ensureDay(date: String, zoneId: String)

    @Query("UPDATE pending_days SET zone_id = :zoneId, version = version + 1 WHERE date = :date")
    suspend fun touchDay(date: String, zoneId: String)

    @Query("SELECT * FROM pending_days WHERE version > published_version ORDER BY date")
    suspend fun changedDays(): List<PendingDayRow>

    @Query("SELECT * FROM pending_usage WHERE date = :date ORDER BY hour, package_name")
    suspend fun usage(date: String): List<PendingUsageRow>

    @Query("SELECT * FROM pending_hours WHERE date = :date ORDER BY hour")
    suspend fun hours(date: String): List<PendingHourRow>

    @Query(
        "UPDATE pending_days SET published_version = :version, published_hash = :hash " +
            "WHERE date = :date AND :version >= published_version",
    )
    suspend fun markPublished(date: String, hash: String, version: Long)

    @Insert
    suspend fun insertEvent(row: PendingEventRow)

    @Query("SELECT * FROM pending_events ORDER BY id")
    suspend fun events(): List<PendingEventRow>

    @Query("DELETE FROM pending_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("SELECT * FROM collector_state WHERE id = 0")
    suspend fun state(): CollectorStateRow?

    /** REPLACE, not @Upsert (see VectorDao.setContig): the collector always writes every column of its one row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(row: CollectorStateRow)

    @Query("SELECT last_usage_publish_ms FROM publish_state WHERE id = 0")
    suspend fun lastUsagePublish(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPublishState(row: PublishStateRow)

    @Query("SELECT hash FROM catalog_state WHERE package_name = :packageName")
    suspend fun catalogHash(packageName: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCatalogHash(row: CatalogStateRow)

    @Query("DELETE FROM pending_usage WHERE date < :before")
    suspend fun pruneUsage(before: String)

    @Query("DELETE FROM pending_hours WHERE date < :before")
    suspend fun pruneHours(before: String)

    @Query("DELETE FROM pending_days WHERE date < :before")
    suspend fun pruneDays(before: String)

    @Query("DELETE FROM pending_events WHERE to_ms < :beforeMs")
    suspend fun pruneEvents(beforeMs: Long)

    /** Packages with time on a day that was published at least once and no AppCatalog entry yet. */
    @Query(
        "SELECT DISTINCT u.package_name FROM pending_usage u JOIN pending_days d ON d.date = u.date " +
            "WHERE d.published_version > 0 AND u.foreground_ms > 0 " +
            "AND u.package_name NOT IN (SELECT package_name FROM catalog_state) ORDER BY u.package_name",
    )
    suspend fun packagesWithoutCatalog(): List<String>
}

@Dao
interface AuthorDao {
    @Query("SELECT * FROM author_state WHERE author = :author")
    suspend fun get(author: String): AuthorStateRow?

    /** REPLACE: the row has one key and every column is supplied. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(row: AuthorStateRow)
}

/** Empties the database for a new group or identity (LocalData.wipeAll); one statement per table of the schema. */
@Dao
interface WipeDao {
    @Query("DELETE FROM entries")
    suspend fun entries()

    @Query("DELETE FROM vector_contig")
    suspend fun vectorContig()

    @Query("DELETE FROM vector_above")
    suspend fun vectorAbove()

    @Query("DELETE FROM pending_usage")
    suspend fun pendingUsage()

    @Query("DELETE FROM pending_hours")
    suspend fun pendingHours()

    @Query("DELETE FROM pending_days")
    suspend fun pendingDays()

    @Query("DELETE FROM pending_events")
    suspend fun pendingEvents()

    @Query("DELETE FROM collector_state")
    suspend fun collectorState()

    @Query("DELETE FROM publish_state")
    suspend fun publishState()

    @Query("DELETE FROM author_state")
    suspend fun authorState()

    @Query("DELETE FROM catalog_state")
    suspend fun catalogState()
}
