package app.kinsight.core.database

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomEntryStoreTest {
    private val dir = Files.createTempDirectory("kinsight-store").toFile()
    private val path = File(dir, "store.db").absolutePath
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "a")
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1)
    private val opened = mutableListOf<KinsightDatabase>()

    private fun open(): RoomEntryStore {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        opened += db
        return RoomEntryStore(db, genesis.groupId)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun failedBatchLeavesNoTrace() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val e2 = author.summary("2026-10-08", 1)
        val e3 = author.summary("2026-10-09", 1)
        store.faultInjector = { i -> if (i == 1) throw IllegalStateException("injected") }
        val r = runCatching { store.applyBatch(listOf(e2, e3), seen = mapOf(keys.deviceId to setOf(9L))) }
        store.faultInjector = null
        assertTrue(r.isFailure)
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertFalse(store.isAccounted(keys.deviceId, 2))
        assertFalse(store.isAccounted(keys.deviceId, 9))
        assertEquals(listOf(genesis.entry), store.liveEntries())
        // The same batch goes through once the fault is gone.
        store.applyBatch(listOf(e2, e3))
        assertEquals(3L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun faultAfterSeenAccountingRollsBackTheSeenSeqs() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val e2 = author.summary("2026-10-08", 1)
        store.faultInjector = { i -> if (i < 0) throw IllegalStateException("injected after seen") }
        val r = runCatching { store.applyBatch(listOf(e2), seen = mapOf(keys.deviceId to setOf(9L))) }
        store.faultInjector = null
        assertTrue(r.isFailure)
        assertFalse(store.isAccounted(keys.deviceId, 9))
        assertFalse(store.isAccounted(keys.deviceId, 2))
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(genesis.entry), store.liveEntries())
    }

    @Test
    fun reopenKeepsEntriesVectorAndSparseAccounting() = runTest {
        val first = open()
        val e2 = author.summary("2026-10-07", 1)
        val e3 = author.summary("2026-10-08", 1)
        first.applyBatch(listOf(genesis.entry, e3), seen = mapOf(keys.deviceId to setOf(5L)))
        opened.removeLast().close()

        val store = open()
        assertEquals(1L, store.versionVector()[keys.deviceId])
        assertTrue(store.isAccounted(keys.deviceId, 3))
        assertTrue(store.isAccounted(keys.deviceId, 5))
        assertFalse(store.isAccounted(keys.deviceId, 4))
        assertEquals(listOf(genesis.entry, e3), store.liveEntries())
        store.applyBatch(listOf(e2))
        assertEquals(3L, store.versionVector()[keys.deviceId])
        store.completeAuthor(keys.deviceId, 4)
        assertEquals(5L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun detailsReadsOneAuthorsDatesInRange() = runTest {
        val store = open()
        val other = EntryAuthor(genesis.groupId, DeviceKeys.generate(), lastSeq = 0)
        store.applyBatch(
            listOf(
                genesis.entry,
                author.detail("2026-09-30"), author.detail("2026-10-01"), author.summary("2026-10-02", 1),
                author.detail("2026-10-07"), author.detail("2026-10-08"), other.detail("2026-10-03"),
            ),
        )
        val got = store.details(keys.deviceId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        assertEquals(listOf("2026-10-01", "2026-10-07"), got.map { it.body.usage_detail!!.local_date })
    }

    @Test
    fun detailsReturnsAMalformedDateInRangeAndOnlyTheNewestVersion() = runTest {
        val store = open()
        val old = author.detail("2026-10-03")
        val bad = author.detail("2026-10-01x") // sorts inside the key range; the read model drops it (usageDate null)
        val newer = author.detail("2026-10-03")
        store.applyBatch(listOf(genesis.entry, old, bad, newer))
        val got = store.details(keys.deviceId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        assertEquals(listOf("2026-10-01x", "2026-10-03"), got.map { it.body.usage_detail!!.local_date })
        assertEquals(null, got[0].usageDate)
        assertEquals(newer.seq, got[1].seq)
    }
}
