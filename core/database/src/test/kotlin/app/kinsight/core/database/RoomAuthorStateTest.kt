package app.kinsight.core.database

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.detail
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RoomAuthorStateTest {
    private val dir = Files.createTempDirectory("kinsight-author").toFile()
    private val path = File(dir, "author.db").absolutePath
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "phone")
    private val opened = mutableListOf<KinsightDatabase>()

    private fun open(): RoomEntryStore {
        val db = KinsightDatabaseFactory.open(HostContext(), path)
        opened += db
        return RoomEntryStore(db, genesis.groupId)
    }

    private suspend fun author(store: RoomEntryStore): EntryAuthor {
        val s = store.authorState(keys.deviceId)
        return EntryAuthor(genesis.groupId, keys, s.lastSeq, lastLamport = s.lastLamport)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun stateFallsBackToStoredEntries() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        assertEquals(AuthorState(1, genesis.entry.lamport), store.authorState(keys.deviceId))
        assertEquals(AuthorState(0, 0), store.authorState(DeviceKeys.generate().deviceId))
    }

    @Test
    fun appendOwnStoresAccountsAndPersistsTheState() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val a = author(store)
        val e2 = a.summary("2026-10-08", 5)
        val e3 = a.detail("2026-10-08")
        store.appendOwn(listOf(e2, e3))
        assertEquals(3L, store.versionVector()[keys.deviceId])
        assertEquals(listOf(genesis.entry, e2, e3), store.liveEntries())
        assertEquals(AuthorState(3, e3.lamport), store.authorState(keys.deviceId))
    }

    @Test
    fun appendOwnRejectsASeqThatDoesNotFollow() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val skipping = EntryAuthor(genesis.groupId, keys, lastSeq = 4).summary("2026-10-08", 1)
        assertFailsWith<IllegalStateException> { store.appendOwn(listOf(skipping)) }
        assertEquals(listOf(genesis.entry), store.liveEntries())
        assertEquals(1L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun failureInsideTheTransactionLeavesNoTraceAndNoGap() = runTest {
        val store = open()
        store.applyBatch(listOf(genesis.entry))
        val e2 = author(store).summary("2026-10-08", 1)
        assertFailsWith<IllegalStateException> { store.appendOwn(listOf(e2)) { error("injected") } }
        assertEquals(AuthorState(1, genesis.entry.lamport), store.authorState(keys.deviceId))
        assertFalse(store.isAccounted(keys.deviceId, 2))
        assertEquals(listOf(genesis.entry), store.liveEntries())
        // A fresh author reuses the seq nobody saw, so the log has no gap.
        val again = author(store).summary("2026-10-08", 2)
        assertEquals(2L, again.seq)
        store.appendOwn(listOf(again))
        assertEquals(2L, store.versionVector()[keys.deviceId])
    }

    @Test
    fun restartKeepsTheSeqAfterOwnEntriesArePruned() = runTest {
        val first = open()
        first.applyBatch(listOf(genesis.entry))
        val old = author(first).detail("2020-01-01")
        first.appendOwn(listOf(old))
        first.prune(SyncPolicy(self = keys.deviceId, today = { TODAY }))
        assertEquals(listOf(genesis.entry), first.liveEntries())
        opened.removeLast().close()

        val store = open()
        assertEquals(AuthorState(2, old.lamport), store.authorState(keys.deviceId))
        assertEquals(3L, author(store).summary("2026-10-09", 1).seq)
    }
}
