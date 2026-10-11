package app.kinsight.core.database

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.File
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
        val v2 = File(dir, "v2.db").absolutePath
        ExportedSchema.create(v2, 2) { c ->
            listOf(genesis.entry, summary, catalog, paused, left).forEach { insertV2(c, it) }
            c.execSQL("INSERT INTO vector_contig VALUES ('${keys.deviceId.hex}', 5)")
        }
        val db = KinsightDatabaseFactory.open(HostContext(), v2)
        val store = RoomEntryStore(db, genesis.groupId)
        assertEquals(listOf(summary), store.summaries())
        assertEquals(listOf(catalog), store.catalog())
        assertEquals(listOf(paused, left), store.stateEvents())
        assertEquals(listOf(genesis.entry, left), store.membershipEntries())
        assertEquals(5L, store.versionVector()[keys.deviceId])
        val row = db.entryDao().get(keys.deviceId.hex, paused.seq)!!
        assertEquals(EntryKinds.STATE_EVENT, row.kind)
        assertEquals(StateEvent.Kind.SHARING_PAUSED.value, row.eventKind)
        assertEquals(7L, row.eventToMs)
        assertEquals(EntryKinds.MEMBERSHIP_OP, db.entryDao().get(keys.deviceId.hex, 1)!!.kind)
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
}
