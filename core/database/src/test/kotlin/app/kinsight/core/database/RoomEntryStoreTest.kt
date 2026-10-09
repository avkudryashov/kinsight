package app.kinsight.core.database

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
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
}
