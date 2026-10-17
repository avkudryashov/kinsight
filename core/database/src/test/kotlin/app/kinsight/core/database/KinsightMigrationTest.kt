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
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.storageHandoverEntry
import app.kinsight.core.sync.categoryOverride
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import app.kinsight.core.transport.cloud.CloudCursor
import app.kinsight.core.transport.cloud.KeyId
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
    fun migrationSqlMatchesExportedV5Schema() {
        val json = schemaJson(5)
        assertTrue(json.contains("\"tableName\": \"cloud_cursor\""), "5.json has no cloud_cursor")
        assertTrue(json.contains(V5_CLOUD_CURSOR_TABLE), "5.json lacks: $V5_CLOUD_CURSOR_TABLE")
        assertTrue(json.contains(V3_ENTRIES_TABLE), "5.json lacks: $V3_ENTRIES_TABLE")
    }

    @Test
    fun migratesV4KeepingEntriesAndAddingTheCloudCursor() = runTest {
        val keys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(keys, "Mom")
        val v4 = File(dir, "v4.db").absolutePath
        ExportedSchema.create(v4, 4) { c -> insertV3(c, genesis.entry, kind = EntryKinds.MEMBERSHIP_OP) }
        val db = KinsightDatabaseFactory.open(HostContext(), v4)
        assertTrue(db.entryDao().get(keys.deviceId.hex, genesis.entry.seq) != null)
        val cursors = RoomCloudCursorStore(db)
        assertEquals(CloudCursor(), cursors.load())
        val c = CloudCursor(uploadedUpTo = 7, seen = mapOf("a/b/c" to 1), ownPacks = mapOf("n" to KeyId(1, "ff")))
        cursors.save(c)
        assertEquals(c, cursors.load())
        db.close()
    }

    @Test
    fun anUnreadableCursorRowReadsAsAFreshCursor() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "c.db").absolutePath)
        db.cloudCursorDao().set(CloudCursorRow(state = byteArrayOf(42)))
        assertEquals(CloudCursor(), RoomCloudCursorStore(db).load())
        db.close()
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
        val zoneId = ByteArray(16) { 7 }.toByteString()
        val oldAccess = author.create { it.copy(cloud_access = CloudAccess(generation = 1, storage_id = ByteArray(16).toByteString())) }
        val access = author.create { it.copy(cloud_access = CloudAccess(generation = 2, storage_id = ByteArray(16).toByteString())) }
        val point = author.create { it.copy(location = Location(lat_e7 = 1, lon_e7 = 2)) }
        val zone = author.create { it.copy(safe_zone = SafeZone(zone_id = zoneId, name = "Дом", radius_m = 100)) }
        val zoneExit = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.ZONE_EXIT, from_ms = 1, to_ms = 9, zone_id = zoneId)) }
        val summary = author.summary("2026-10-08", 60_000)
        val v3 = File(dir, "v3.db").absolutePath
        ExportedSchema.create(v3, 3) { c ->
            // A 0.8.x build stored these with kind 0 (UNKNOWN) and no supersede key: it could not decode the payload.
            for (e in listOf(oldAccess, access, point, zone)) insertV3(c, e, kind = EntryKinds.UNKNOWN, key = null)
            // ...and a StateEvent of a kind it did not know with event_kind 0 (unknown enum = KIND_UNSPECIFIED).
            insertV3(c, zoneExit, kind = EntryKinds.STATE_EVENT, key = null, eventKind = 0, eventToMs = 9)
            insertV3(c, summary, kind = EntryKinds.USAGE_SUMMARY)
        }
        val db = KinsightDatabaseFactory.open(HostContext(), v3)
        val dao = db.entryDao()
        val a = keys.deviceId.hex
        assertNull(dao.get(a, oldAccess.seq), "the older CloudAccess is superseded")
        assertEquals(EntryKinds.CLOUD_ACCESS, dao.get(a, access.seq)!!.kind)
        assertEquals("$a/access", dao.get(a, access.seq)!!.supersedeKey)
        assertEquals(EntryKinds.LOCATION, dao.get(a, point.seq)!!.kind)
        assertNull(dao.get(a, point.seq)!!.supersedeKey)
        assertEquals(EntryKinds.SAFE_ZONE, dao.get(a, zone.seq)!!.kind)
        assertEquals("$a/zone/${"07".repeat(16)}", dao.get(a, zone.seq)!!.supersedeKey)
        val exit = dao.get(a, zoneExit.seq)!!
        assertEquals(EntryKinds.STATE_EVENT, exit.kind)
        assertEquals(StateEvent.Kind.ZONE_EXIT.value, exit.eventKind)
        assertEquals(EntryKinds.USAGE_SUMMARY, dao.get(a, summary.seq)!!.kind)
        db.close()
    }

    @Test
    fun migratesV3GivingAStoredUnknownHandoverItsKind() = runTest {
        val keys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(keys, "Mom")
        val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
        val dad = DeviceKeys.generate().deviceId
        val old = author.storageHandoverEntry(dad, 1)
        val newer = author.storageHandoverEntry(dad, 2)
        val v3 = File(dir, "v3h.db").absolutePath
        // A released 0.8.x build (schema 3) could not decode the payload: kind 0, no supersede key.
        ExportedSchema.create(v3, 3) { c -> for (e in listOf(old, newer)) insertV3(c, e, kind = EntryKinds.UNKNOWN, key = null) }
        val db = KinsightDatabaseFactory.open(HostContext(), v3)
        val a = keys.deviceId.hex
        assertNull(db.entryDao().get(a, old.seq), "the older handover is superseded")
        assertEquals(EntryKinds.STORAGE_HANDOVER, db.entryDao().get(a, newer.seq)!!.kind)
        assertEquals("$a/handover", db.entryDao().get(a, newer.seq)!!.supersedeKey)
        db.close()
    }

    @Test
    fun migratesV3GivingAStoredUnknownParentPinItsKind() = runTest {
        val keys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(keys, "Mom")
        val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
        val old = author.parentPinEntry(ByteArray(16) { 1 }, ByteArray(32) { 2 }, 19_456, 2, rank = 1)
        val newer = author.parentPinEntry(ByteArray(16) { 3 }, ByteArray(32) { 4 }, 19_456, 2, rank = 2)
        val v3 = File(dir, "v3p.db").absolutePath
        // A released build (schema 3) could not decode the payload: kind 0, no supersede key.
        ExportedSchema.create(v3, 3) { c -> for (e in listOf(old, newer)) insertV3(c, e, kind = EntryKinds.UNKNOWN, key = null) }
        val db = KinsightDatabaseFactory.open(HostContext(), v3)
        val a = keys.deviceId.hex
        assertNull(db.entryDao().get(a, old.seq), "the older PIN is superseded")
        assertEquals(EntryKinds.PARENT_PIN, db.entryDao().get(a, newer.seq)!!.kind)
        assertEquals("$a/pin", db.entryDao().get(a, newer.seq)!!.supersedeKey)
        assertEquals(listOf(newer), RoomEntryStore(db, genesis.groupId).parentPins())
        db.close()
    }

    /** Y5 T2 parked minor: the PIN supersede key is per author, so two parents' PINs both survive the migration. */
    @Test
    fun migratesV3KeepingOneParentPinPerAuthor() = runTest {
        val momKeys = DeviceKeys.generate()
        val dadKeys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(momKeys, "Mom")
        val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
        val dad = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0)
        val momOld = mom.parentPinEntry(ByteArray(16) { 1 }, ByteArray(32) { 2 }, 19_456, 2, rank = 1)
        val momNew = mom.parentPinEntry(ByteArray(16) { 3 }, ByteArray(32) { 4 }, 19_456, 2, rank = 2)
        val dadPin = dad.parentPinEntry(ByteArray(16) { 5 }, ByteArray(32) { 6 }, 19_456, 2, rank = 1)
        val v3 = File(dir, "v3pp.db").absolutePath
        ExportedSchema.create(v3, 3) { c -> for (e in listOf(momOld, dadPin, momNew)) insertV3(c, e, kind = EntryKinds.UNKNOWN, key = null) }
        val db = KinsightDatabaseFactory.open(HostContext(), v3)
        assertNull(db.entryDao().get(momKeys.deviceId.hex, momOld.seq), "Mom's older PIN is superseded")
        assertEquals("${dadKeys.deviceId.hex}/pin", db.entryDao().get(dadKeys.deviceId.hex, dadPin.seq)!!.supersedeKey)
        assertEquals(setOf(momNew, dadPin), RoomEntryStore(db, genesis.groupId).parentPins().toSet())
        db.close()
    }

    private fun insertV3(
        c: SQLiteConnection, e: DecodedEntry, kind: Int, key: String? = e.supersedeKey, eventKind: Int? = null, eventToMs: Long? = null,
    ) {
        c.prepare(
            "INSERT INTO entries (author, seq, kind, supersede_key, membership, usage_date, event_kind, event_to_ms, bytes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.bindText(1, e.author.hex)
            st.bindLong(2, e.seq)
            st.bindLong(3, kind.toLong())
            key?.let { st.bindText(4, it) } ?: st.bindNull(4)
            st.bindLong(5, if (e.isMembershipRelevant) 1 else 0)
            (e.body.usage_summary?.local_date ?: e.body.usage_detail?.local_date)?.let { st.bindText(6, it) } ?: st.bindNull(6)
            eventKind?.let { st.bindLong(7, it.toLong()) } ?: st.bindNull(7)
            eventToMs?.let { st.bindLong(8, it) } ?: st.bindNull(8)
            st.bindBlob(9, e.bytes)
            st.step()
        }
    }
}
