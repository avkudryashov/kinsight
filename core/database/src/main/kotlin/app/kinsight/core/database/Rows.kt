package app.kinsight.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One signed entry in its original bytes (spec §4.2) plus the fields the store indexes on. */
@Entity(
    tableName = "entries",
    primaryKeys = ["author", "seq"],
    indices = [Index(value = ["supersede_key"], unique = true), Index(value = ["membership"])],
)
class EntryRow(
    /** DeviceId hex: ORDER BY author matches DeviceId.hex ordering. */
    val author: String,
    val seq: Long,
    val bytes: ByteArray,
    @ColumnInfo(name = "supersede_key") val supersedeKey: String?,
    /** DecodedEntry.isMembershipRelevant: membership ops and LEFT_GROUP events. */
    val membership: Boolean,
    /** local_date of UsageSummary/UsageDetail; only these rows can expire. */
    @ColumnInfo(name = "usage_date") val usageDate: String?,
)

/** Contiguous part of the version vector: every seq ≤ contig of [author] is accounted for. */
@Entity(tableName = "vector_contig")
class ContigRow(@PrimaryKey val author: String, val contig: Long)

/** Sparse accounted seqs above the contiguous part. */
@Entity(tableName = "vector_above", primaryKeys = ["author", "seq"])
class AboveRow(val author: String, val seq: Long)
