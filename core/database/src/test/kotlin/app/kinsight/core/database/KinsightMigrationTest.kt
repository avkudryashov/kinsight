package app.kinsight.core.database

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.CloudAccess
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.SafeZone
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.categoryOverride
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import java.time.LocalDate
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KinsightMigrationTest {
    private val dir = Files.createTempDirectory("kinsight-migration").toFile()
    private val path = File(dir, "v1.db").absolutePath
    private val author = "aa".repeat(16)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun schemaJson(version: Int) =
        File("schemas/app.kinsight.core.database.KinsightDatabase/$version.json").readText()

    @Test
    fun frozenV1SchemaMatchesTheExportedJson() {
        val json = schemaJson(1)
        for ((_, sql) in V1Schema.TABLES) assertTrue(json.contains(sql), "1.json lacks: $sql")
        assertTrue(json.contains(V1Schema.IDENTITY_HASH))
    }

    @Test
    fun migrationSqlMatchesExportedV2Schema() {
        val json = schemaJson(2)
        assertEquals(8, V2_NEW_TABLES.size)
        for ((table, sql) in V2_NEW_TABLES) {
            assertTrue(json.contains("\"tableName\": \"$table\""), "2.json has no table $table")
            assertTrue(json.contains(sql), "2.json lacks: $sql")
        }
    }

    @Test
    fun migrationSqlMatchesExportedV3Schema() {
        val json = schemaJson(3)
        assertTrue(json.contains(V3_ENTRIES_TABLE), "3.json lacks: $V3_ENTRIES_TABLE")
        for (sql in V3_ENTRIES_INDICES) assertTrue(json.contains(sql), "3.json lacks: $sql")
    }

    private fun insertV2(c: SQLiteConnection, e: DecodedEntry) {
        c.prepare("INSERT INTO entries (author, seq, bytes, supersede_key, membership, usage_date) VALUES (?, ?, ?, ?, ?, ?)").use { st ->
            st.bindText(1, e.author.hex)
            st.bindLong(2, e.seq)
            st.bindBlob(3, e.bytes)
            e.supersedeKey?.let { st.bindText(4, it) } ?: st.bindNull(4)
            st.bindLong(5, if (e.isMembershipRelevant) 1 else 0)
            (e.body.usage_summary?.local_date ?: e.body.usage_detail?.local_date)?.let { st.bindText(6, it) } ?: st.bindNull(6)
            st.step()
        }
    }

    @Test
    fun migratesV2DerivingKindsAndKeepingEveryRow() = runTest {
        val keys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(keys, "Mom")
        val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
        val summary = author.summary("2026-10-08", 60_000)
        val catalog = author.create { it.copy(app_catalog = AppCatalog(package_name = "com.example.maps", label = "Maps")) }
        val paused = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.SHARING_PAUSED, from_ms = 5, to_ms = 7)) }
        val left = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP, from_ms = 9, to_ms = 9)) }
        val detail = author.detail("2026-10-08")
        val beat = author.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 1)) }
        val override = author.categoryOverride("com.example.game", 1)
        // A payload field this build does not know (EntryBody field 99): decodes, but has no kind here.
        val future = author.create { it.copy(unknownFields = "9a06020801".decodeHex()) }
        val a = keys.deviceId.hex
        val garbage = "bb".repeat(16)
        val v2 = File(dir, "v2.db").absolutePath
        ExportedSchema.create(v2, 2) { c ->
            listOf(genesis.entry, summary, catalog, paused, left, detail, beat, override, future).forEach { insertV2(c, it) }
            c.execSQL("INSERT INTO entries (author, seq, bytes, supersede_key, membership, usage_date) VALUES ('$garbage', 1, x'ff00ff', NULL, 0, NULL)")
            c.execSQL("INSERT INTO vector_contig VALUES ('$a', 10)")
        }
        val db = KinsightDatabaseFactory.open(HostContext(), v2)
        val store = RoomEntryStore(db, genesis.groupId)
        assertEquals(listOf(summary), store.summaries())
        val date = LocalDate.parse("2026-10-08")
        assertEquals(listOf(detail), store.details(keys.deviceId, date, date))
        assertEquals(listOf(catalog), store.catalog())
        assertEquals(listOf(paused, left), store.stateEvents())
        assertEquals(listOf(beat), store.heartbeats())
        assertEquals(listOf(override), store.categoryOverrides())
        assertEquals(listOf(genesis.entry, left), store.membershipEntries())
        assertEquals(10L, store.versionVector()[keys.deviceId])
        val dao = db.entryDao()
        assertEquals(10, dao.all().size) // every row survives the rebuild
        val expected = mapOf(
            genesis.entry to EntryKinds.MEMBERSHIP_OP, summary to EntryKinds.USAGE_SUMMARY, detail to EntryKinds.USAGE_DETAIL,
            catalog to EntryKinds.APP_CATALOG, paused to EntryKinds.STATE_EVENT, left to EntryKinds.STATE_EVENT,
            beat to EntryKinds.HEARTBEAT, override to EntryKinds.CATEGORY_OVERRIDE, future to EntryKinds.UNKNOWN,
        )
        for ((e, kind) in expected) assertEquals(kind, dao.get(a, e.seq)!!.kind, "seq ${e.seq}")
        for (k in 1..7) assertTrue(dao.bytesOfKind(k).isNotEmpty(), "no row of kind $k")
        val row = dao.get(a, paused.seq)!!
        assertEquals(StateEvent.Kind.SHARING_PAUSED.value, row.eventKind)
        assertEquals(7L, row.eventToMs)
        assertNull(dao.get(a, beat.seq)!!.eventKind)
        val undecodable = dao.get(garbage, 1)!!
        assertEquals(EntryKinds.UNKNOWN, undecodable.kind)
        assertNull(undecodable.eventKind)
        assertContentEquals(byteArrayOf(-1, 0, -1), undecodable.bytes)
        db.close()
    }

    @Test
    fun kindQueriesUseTheIndexWithoutSorting() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        val plan = db.useReaderConnection { c ->
            c.usePrepared("EXPLAIN QUERY PLAN SELECT bytes FROM entries WHERE kind = 1 ORDER BY author, seq") { st ->
                buildList { while (st.step()) add(st.getText(3)) }
            }
        }
        assertTrue(plan.any { "index_entries_kind_author_seq" in it }, plan.toString())
        assertTrue(plan.none { "TEMP B-TREE" in it }, plan.toString())
        db.close()
    }

    @Test
    fun migratesV1KeepingEntriesAndVector() = runTest {
        V1Schema.create(path) { c ->
            c.execSQL("INSERT INTO entries VALUES ('$author', 1, x'0102', 'k1', 1, NULL)")
            c.execSQL("INSERT INTO entries VALUES ('$author', 2, x'03', NULL, 0, '2026-10-08')")
            c.execSQL("INSERT INTO vector_contig VALUES ('$author', 2)")
            c.execSQL("INSERT INTO vector_above VALUES ('$author', 5)")
        }
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        assertContentEquals(byteArrayOf(1, 2), db.entryDao().get(author, 1)!!.bytes)
        assertEquals(EntryKinds.UNKNOWN, db.entryDao().get(author, 1)!!.kind) // undecodable bytes survive the rebuild
        assertEquals("2026-10-08", db.entryDao().get(author, 2)!!.usageDate)
        assertEquals(2L, db.vectorDao().contig(author))
        assertEquals(listOf(5L), db.vectorDao().above(author))
        assertEquals(2L, db.entryDao().maxSeq(author))
        assertNull(db.usageDao().state())
        assertTrue(db.usageDao().changedDays().isEmpty())
        assertTrue(db.usageDao().events().isEmpty())
        assertNull(db.authorDao().get(author))
        db.close()
        // The migrated file now opens as a plain version 3 database.
        val again = KinsightDatabaseFactory.open(HostContext(), path)
        assertEquals(2, again.entryDao().all().size)
        again.close()
    }

    @Test
    fun sharedIsOneInstancePerProcess() = runTest {
        val context = HostContext(dir)
        try {
            val first = KinsightDatabaseFactory.shared(context)
            assertSame(first, KinsightDatabaseFactory.shared(context))
            first.entryDao().all()
            assertTrue(File(dir, KinsightDatabaseFactory.DEFAULT_NAME).exists())
        } finally {
            KinsightDatabaseFactory.resetShared()
        }
    }

    @Test
    fun v4SchemaKeepsTheV3Tables() {
        val json = schemaJson(4)
        assertTrue(json.contains(V3_ENTRIES_TABLE), "4.json lacks: $V3_ENTRIES_TABLE")
        for (sql in V3_ENTRIES_INDICES) assertTrue(json.contains(sql), "4.json lacks: $sql")
    }

    @Test
    fun migratesV3GivingStoredUnknownRowsTheirNewKinds() = runTest {
        val keys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(keys, "Mom")
        val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
        val access = author.create { it.copy(cloud_access = CloudAccess(generation = 1, storage_id = ByteArray(16).toByteString())) }
        val point = author.create { it.copy(location = Location(lat_e7 = 1, lon_e7 = 2)) }
        val zone = author.create { it.copy(safe_zone = SafeZone(zone_id = ByteArray(16).toByteString(), name = "Дом", radius_m = 100)) }
        val summary = author.summary("2026-10-08", 60_000)
        val v3 = File(dir, "v3.db").absolutePath
        ExportedSchema.create(v3, 3) { c ->
            // A 0.8.x build stored these with kind 0 (UNKNOWN): it could not decode the payload.
            for (e in listOf(access, point, zone)) insertV3(c, e, kind = EntryKinds.UNKNOWN)
            insertV3(c, summary, kind = EntryKinds.USAGE_SUMMARY)
        }
        val db = KinsightDatabaseFactory.open(HostContext(), v3)
        val dao = db.entryDao()
        val a = keys.deviceId.hex
        assertEquals(EntryKinds.CLOUD_ACCESS, dao.get(a, access.seq)!!.kind)
        assertEquals(EntryKinds.LOCATION, dao.get(a, point.seq)!!.kind)
        assertEquals(EntryKinds.SAFE_ZONE, dao.get(a, zone.seq)!!.kind)
        assertEquals(EntryKinds.USAGE_SUMMARY, dao.get(a, summary.seq)!!.kind)
        db.close()
    }

    private fun insertV3(c: SQLiteConnection, e: DecodedEntry, kind: Int) {
        c.prepare(
            "INSERT INTO entries (author, seq, kind, supersede_key, membership, usage_date, event_kind, event_to_ms, bytes) " +
                "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
        ).use { st ->
            st.bindText(1, e.author.hex)
            st.bindLong(2, e.seq)
            st.bindLong(3, kind.toLong())
            e.supersedeKey?.let { st.bindText(4, it) } ?: st.bindNull(4)
            st.bindLong(5, if (e.isMembershipRelevant) 1 else 0)
            (e.body.usage_summary?.local_date ?: e.body.usage_detail?.local_date)?.let { st.bindText(6, it) } ?: st.bindNull(6)
            st.bindBlob(7, e.bytes)
            st.step()
        }
    }
}
