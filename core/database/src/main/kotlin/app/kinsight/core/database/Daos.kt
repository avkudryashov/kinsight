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
}

@Dao
interface VectorDao {
    @Query("SELECT * FROM vector_contig WHERE contig > 0")
    suspend fun positive(): List<ContigRow>

    @Query("SELECT contig FROM vector_contig WHERE author = :author")
    suspend fun contig(author: String): Long?

    /**
     * REPLACE rather than @Upsert: Room's upsert detects the conflict from the SQLiteException message, which the
     * mockable android.jar drops in JVM tests; vector_contig has one primary key and no foreign keys or triggers.
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
