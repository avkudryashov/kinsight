package app.kinsight.core.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KinsightDatabaseTest {
    private val dir = Files.createTempDirectory("kinsight-db").toFile()
    private val path = File(dir, "test.db").absolutePath

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun row(seq: Long, key: String? = null) =
        EntryRow(author = "aa".repeat(16), seq = seq, bytes = byteArrayOf(seq.toByte()), supersedeKey = key, membership = false, usageDate = null)

    @Test
    fun persistsRowsAcrossReopen() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        db.useWriterConnection { it.immediateTransaction { db.entryDao().insert(row(1)) } }
        db.close()
        val again = KinsightDatabaseFactory.open(HostContext(), path)
        assertContentEquals(byteArrayOf(1), again.entryDao().get("aa".repeat(16), 1)!!.bytes)
        again.close()
    }

    @Test
    fun failedTransactionRollsBack() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        val r = runCatching {
            db.useWriterConnection {
                it.immediateTransaction {
                    db.entryDao().insert(row(1))
                    error("boom")
                }
            }
        }
        assertTrue(r.isFailure)
        assertNull(db.entryDao().get("aa".repeat(16), 1))
        db.close()
    }

    @Test
    fun supersedeKeyIsUnique() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        db.entryDao().insert(row(1, key = "k"))
        assertTrue(runCatching { db.entryDao().insert(row(2, key = "k")) }.isFailure)
        db.entryDao().insert(row(3, key = null))
        db.entryDao().insert(row(4, key = null)) // NULL keys never collide
        assertEquals(3, db.entryDao().all().size)
        db.close()
    }

    @Test
    fun vectorTablesRoundTrip() = runTest {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        val vector = db.vectorDao()
        vector.setContig(ContigRow("bb", 3))
        vector.setContig(ContigRow("bb", 5))
        vector.setContig(ContigRow("cc", 0))
        vector.addAbove(AboveRow("bb", 9))
        vector.addAbove(AboveRow("bb", 7))
        vector.addAbove(AboveRow("bb", 7))
        assertEquals(listOf("bb" to 5L), vector.positive().map { it.author to it.contig })
        assertEquals(listOf(7L, 9L), vector.above("bb"))
        assertTrue(vector.isAccounted("bb", 4))
        assertTrue(vector.isAccounted("bb", 9))
        assertTrue(!vector.isAccounted("bb", 8))
        vector.deleteAboveUpTo("bb", 7)
        assertEquals(listOf(9L), vector.above("bb"))
        db.close()
    }
}
