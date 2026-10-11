package app.kinsight.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One signed entry in its original bytes (spec §4.2) plus the fields the store indexes on. Schema 3: the screens read by
 * [kind] through the (kind, author, seq) index, and [bytes] is the last column, so SQLite reads the other columns of a
 * row without walking the BLOB's overflow pages (audit A-5).
 */
@Entity(
    tableName = "entries",
    primaryKeys = ["author", "seq"],
    indices = [
        Index(value = ["supersede_key"], unique = true),
        Index(value = ["membership"]),
        Index(value = ["kind", "author", "seq"]),
    ],
)
class EntryRow(
    /** DeviceId hex: ORDER BY author matches DeviceId.hex ordering. */
    val author: String,
    val seq: Long,
    /** [EntryKinds] code of the payload. */
    val kind: Int,
    @ColumnInfo(name = "supersede_key") val supersedeKey: String?,
    /** DecodedEntry.isMembershipRelevant: membership ops and LEFT_GROUP events. */
    val membership: Boolean,
    /** local_date of UsageSummary/UsageDetail; only these rows can expire. */
    @ColumnInfo(name = "usage_date") val usageDate: String?,
    /** StateEvent.Kind value of a StateEvent, else null: history pruning keeps each author's newest per kind. */
    @ColumnInfo(name = "event_kind") val eventKind: Int?,
    /** StateEvent.to_ms of a StateEvent, else null: the age history pruning compares with the retention. */
    @ColumnInfo(name = "event_to_ms") val eventToMs: Long?,
    val bytes: ByteArray,
)

/** Contiguous part of the version vector: every seq ≤ contig of [author] is accounted for. */
@Entity(tableName = "vector_contig")
class ContigRow(@PrimaryKey val author: String, val contig: Long)

/** Sparse accounted seqs above the contiguous part. */
@Entity(tableName = "vector_above", primaryKeys = ["author", "seq"])
class AboveRow(val author: String, val seq: Long)

/** Local unsigned usage queue (spec §7): foreground time of one app in one local hour, capped at the hour length. */
@Entity(tableName = "pending_usage", primaryKeys = ["date", "hour", "package_name"])
data class PendingUsageRow(
    val date: String,
    val hour: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "foreground_ms") val foregroundMs: Long,
)

/** Per local hour: screen time (union of the foreground apps, launcher excluded) and unlocks. */
@Entity(tableName = "pending_hours", primaryKeys = ["date", "hour"])
data class PendingHourRow(
    val date: String,
    val hour: Int,
    @ColumnInfo(name = "screen_ms") val screenMs: Long,
    val unlocks: Int,
)

/**
 * Per local date: the zone of the latest contribution, a version bumped by every collector commit that touched the
 * date, and what was last published. The date has unpublished changes while version > published_version.
 */
@Entity(tableName = "pending_days")
data class PendingDayRow(
    @PrimaryKey val date: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val version: Long,
    @ColumnInfo(name = "published_version") val publishedVersion: Long,
    @ColumnInfo(name = "published_hash") val publishedHash: String?,
)

/** Integrity event waiting for sharing (spec §7); kind is StateEvent.Kind.value. Published in id order. */
@Entity(tableName = "pending_events")
data class PendingEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: Int,
    @ColumnInfo(name = "from_ms") val fromMs: Long,
    @ColumnInfo(name = "to_ms") val toMs: Long,
    @ColumnInfo(name = "clock_shift_ms") val clockShiftMs: Long = 0,
)

/**
 * The single collector row (id = 0): the cursor, the event-stream state at the cursor, the clock sample of the last
 * run (clock-change detection) and the last observed usage access (null before the first observation).
 */
@Entity(tableName = "collector_state")
data class CollectorStateRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "cursor_ms") val cursorMs: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "screen_on") val screenOn: Boolean,
    /** Resumed activities at the cursor, "package/class" joined by '\n'. */
    val resumed: String,
    @ColumnInfo(name = "wall_ms") val wallMs: Long,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int,
    @ColumnInfo(name = "access_granted") val accessGranted: Boolean?,
    @ColumnInfo(name = "access_lost_ms") val accessLostMs: Long?,
)

/** The single publisher row (id = 0): when usage entries were last created (the hourly limit, spec §7). */
@Entity(tableName = "publish_state")
data class PublishStateRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "last_usage_publish_ms") val lastUsagePublishMs: Long,
)

/** The local author's last used seq and Lamport value, written in the same transaction as the entry (spec §7). */
@Entity(tableName = "author_state")
data class AuthorStateRow(
    @PrimaryKey val author: String,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
    @ColumnInfo(name = "last_lamport") val lastLamport: Long,
)

/** Hash of the last published AppCatalog content per package: re-emitted only on change. */
@Entity(tableName = "catalog_state")
data class CatalogStateRow(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    val hash: String,
)
