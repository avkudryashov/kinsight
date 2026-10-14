package app.kinsight.core.transport.cloud

import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnFolderTest {
    private val disk = InMemoryDisk()

    @Test
    fun aPhoneThatLostItsDatabaseTakesItsOwnPacksBack() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        kid.sync(disk).run()
        // The database is gone, the keys and the ring survived: an empty store with the membership, a fresh cursor.
        val fresh = InMemoryEntryStore(kid.store.groupId).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(kid.store.groupId, kid.keys, lastSeq = 0), fresh, isChild = true, ring = kid.ring)
        assertIs<CloudResult.Synced>(again.sync(disk).run())
        assertEquals(3L, fresh.versionVector()[kid.id], "A-4: those seqs are never signed again")
        assertTrue(fresh.liveEntries().containsAll(days))
        assertEquals(3L, again.cursor.cursor.uploadedUpTo, "the packs on the Disk count as uploaded")
        // A re-seal now writes the entries back, never an empty range: Mom still reads all three.
        again.sync(disk).run(forceCompaction = true)
        mom.sync(disk).run()
        assertTrue(mom.store.liveEntries().containsAll(days))
    }

    @Test
    fun anOwnPackFarAboveIsNotTrusted() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val far = SyncSession.MAX_OWN_SEQ_JUMP + 5
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, far)}", PackCodec.seal(kid.keys, g, emptyList(), 1, far, 0, GEN0, 1_000L))
        kid.sync(disk).run()
        assertEquals(0L, kid.store.versionVector()[kid.id])
    }

    @Test
    fun theOwnerPutsAMissingStorageIdBack() = runTest {
        val (mom) = family()
        val g = mom.store.groupId
        val id = ByteArray(16) { 7 }
        val sync = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L }, ownedStorageId = id)
        sync.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
        disk.deleteTree(CloudPaths.root(g)) // deleted in the Disk UI (spec §11)
        sync.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
    }

    @Test
    fun aMemberNeverWritesTheStorageId() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(disk).run()
        assertTrue(disk.files(CloudPaths.storageIdFile(kid.store.groupId)).isEmpty())
    }
}
