package app.kinsight.core.database

import androidx.sqlite.execSQL
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
    fun migratesV1KeepingEntriesAndVector() = runTest {
        V1Schema.create(path) { c ->
            c.execSQL("INSERT INTO entries VALUES ('$author', 1, x'0102', 'k1', 1, NULL)")
            c.execSQL("INSERT INTO entries VALUES ('$author', 2, x'03', NULL, 0, '2026-10-08')")
            c.execSQL("INSERT INTO vector_contig VALUES ('$author', 2)")
            c.execSQL("INSERT INTO vector_above VALUES ('$author', 5)")
        }
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        assertContentEquals(byteArrayOf(1, 2), db.entryDao().get(author, 1)!!.bytes)
        assertEquals("2026-10-08", db.entryDao().get(author, 2)!!.usageDate)
        assertEquals(2L, db.vectorDao().contig(author))
        assertEquals(listOf(5L), db.vectorDao().above(author))
        assertEquals(2L, db.entryDao().maxSeq(author))
        assertNull(db.usageDao().state())
        assertTrue(db.usageDao().changedDays().isEmpty())
        assertTrue(db.usageDao().events().isEmpty())
        assertNull(db.authorDao().get(author))
        db.close()
        // The migrated file now opens as a plain version 2 database.
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
