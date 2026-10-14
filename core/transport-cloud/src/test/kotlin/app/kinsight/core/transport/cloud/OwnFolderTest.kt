package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
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
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val id = ByteArray(16) { 7 }
        val owner = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L }, ownedStorageId = id)
        owner.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
        disk.delete(CloudPaths.storageIdFile(g)) // only the file, the root stays
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(disk).run()
        assertTrue(disk.files(CloudPaths.storageIdFile(g)).isEmpty())
        // The owner's next run sees the root without it and writes it again.
        owner.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
    }

    @Test
    fun ownPacksWeCannotOpenStayAsTheyAre() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        kid.sync(disk, maxPackEntries = 2).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        val before = disk.files("$dir/")
        assertEquals(2, before.size)
        // The database is gone and the ring holds only a newer generation: no own pack opens.
        val fresh = InMemoryEntryStore(kid.store.groupId).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(kid.store.groupId, kid.keys, lastSeq = 0), fresh, isChild = true, ring = InMemoryKeyRing(mapOf(1 to Primitives.randomBytes(32))))
        assertIs<CloudResult.Synced>(again.sync(disk).run())
        assertEquals(3L, fresh.versionVector()[kid.id], "A-4: the range is ours even unread")
        again.sync(disk).run()
        again.sync(disk).run(forceCompaction = true)
        val after = disk.files("$dir/")
        assertEquals(before.keys, after.keys, "no pack over the kept ranges was written")
        for ((k, v) in before) assertContentEquals(v, after.getValue(k), k)
        // A new entry goes above them.
        val author = EntryAuthor(kid.store.groupId, kid.keys, lastSeq = 3)
        again.publish(author.summary("2026-10-04", 4 * 60_000L))
        again.sync(disk).run()
        val last = disk.files("$dir/")
        assertEquals(before.keys + "$dir/${CloudPaths.packName(4, 4)}", last.keys)
        for ((k, v) in before) assertContentEquals(v, last.getValue(k), k)
    }

    @Test
    fun aFullCompactionKeepsTheUnreadablePacks() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val days = (1..5).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        kid.sync(disk, maxPackEntries = 3).run()
        val dir = CloudPaths.deviceDir(g, kid.id)
        // 4-5 sealed with a generation the fresh phone lacks.
        val tail = kid.store.entriesAfter(kid.id, 3, 10)
        val unreadable = PackCodec.seal(kid.keys, g, tail, 4, 5, 7, Primitives.randomBytes(32), 1_000L)
        disk.upload("$dir/${CloudPaths.packName(4, 5)}", unreadable)
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
        again.sync(disk).run()
        assertEquals(5L, fresh.versionVector()[kid.id])
        assertTrue(fresh.liveEntries().containsAll(days.take(3)))
        again.sync(disk).run(forceCompaction = true)
        val after = disk.files("$dir/")
        assertEquals(setOf("$dir/${CloudPaths.packName(1, 3)}", "$dir/${CloudPaths.packName(4, 5)}"), after.keys)
        assertContentEquals(unreadable, after.getValue("$dir/${CloudPaths.packName(4, 5)}"))
        mom.sync(disk).run()
        assertTrue(mom.store.liveEntries().containsAll(days.take(3)))
    }

    @Test
    fun aPlantedFarNameDoesNotStopTheLift() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        kid.sync(disk).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        disk.upload("$dir/${CloudPaths.packName(1, 20_000_000)}", byteArrayOf(1, 2, 3))
        val fresh = InMemoryEntryStore(kid.store.groupId).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(kid.store.groupId, kid.keys, lastSeq = 0), fresh, isChild = true, ring = kid.ring)
        again.sync(disk).run()
        assertEquals(3L, fresh.versionVector()[kid.id])
        assertTrue(fresh.liveEntries().containsAll(days))
    }
}
