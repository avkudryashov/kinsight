package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.addMember
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

    /**
     * Own membership ops lost with the database (here Mom adding Grandma; the keys and the ring survived) come back with
     * the lift, and the same run already reads with them: Grandma's pack opens in that run instead of waiting, as from an
     * unknown author, for the next one.
     */
    @Test
    fun aMemberLiftedFromOurOwnPacksIsReadInTheSameRun() = runTest {
        val (mom, _) = family(DeviceRole.CHILD)
        val grandmaKeys = DeviceKeys.generate()
        val addGrandma = mom.author.addMember(grandmaKeys.sign.publicKey, grandmaKeys.dh.publicKey, "Grandma")
        mom.publish(addGrandma)
        mom.sync(disk).run()
        val gid = mom.store.groupId
        val grandma = Phone(
            "grandma", grandmaKeys, EntryAuthor(gid, grandmaKeys, lastSeq = 0),
            InMemoryEntryStore(gid).also { it.storeMembership(mom.store.membershipEntries()) }, isChild = false, ring = InMemoryKeyRing(mapOf(0 to GEN0)),
        )
        val day = grandma.author.summary("2026-10-13", 60_000)
        grandma.publish(day)
        grandma.sync(disk).run()
        val fresh = InMemoryEntryStore(gid).also { it.storeMembership(mom.store.membershipEntries() - addGrandma) }
        val again = Phone("mom", mom.keys, EntryAuthor(gid, mom.keys, lastSeq = 0), fresh, isChild = false, ring = mom.ring)
        assertIs<CloudResult.Synced>(again.sync(disk).run())
        assertTrue(grandmaKeys.deviceId in again.membership().active, "lifted from Mom's own pack")
        assertTrue(day in fresh.liveEntries(), "read in the run that lifted the member")
    }

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

    /**
     * Only the file deleted in the Disk UI, the root stays: the owner's next run sees the root without it and writes it
     * again. That a member's run never writes it is pinned where the owned id is chosen (core/data CloudOwnerMoveTest
     * `onlyTheOwnersRunWritesTheStorageId`): a CloudSync without an owned id has nothing to write.
     */
    @Test
    fun theOwnerPutsBackAStorageIdDeletedAlone() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val id = ByteArray(16) { 7 }
        val owner = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L }, ownedStorageId = id)
        owner.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
        disk.delete(CloudPaths.storageIdFile(g))
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(disk).run()
        assertTrue(disk.files(CloudPaths.storageIdFile(g)).isEmpty())
        owner.run()
        assertContentEquals(id, disk.files(CloudPaths.storageIdFile(g)).values.single())
    }

    /** A storage.id of another storage (same size, other bytes) is never overwritten: telling storages apart relies on it. */
    @Test
    fun aStorageIdOfAnotherStorageIsNeverOverwritten() = runTest {
        val (mom, _) = family(DeviceRole.CHILD)
        val g = mom.store.groupId
        val theirs = ByteArray(16) { 9 }
        disk.mkdir(CloudPaths.APP_ROOT)
        disk.mkdir(CloudPaths.root(g))
        disk.upload(CloudPaths.storageIdFile(g), theirs)
        val owner = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L }, ownedStorageId = ByteArray(16) { 7 })
        assertIs<CloudResult.Synced>(owner.run())
        assertIs<CloudResult.Synced>(owner.run())
        assertContentEquals(theirs, disk.files(CloudPaths.storageIdFile(g)).values.single())
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

    /** Parked Y3 minor: a kept (unreadable) own pack overlapped by one we opened holds nothing we lack: it is no hole. */
    @Test
    fun aKeptPackCoveredByAnOpenedOneIsReplacedLikeAnyOther() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        kid.sync(disk).run()
        val dir = CloudPaths.deviceDir(g, kid.id)
        // A compaction cut short left the old 1-2, sealed with a generation nobody holds any more, next to the new 1-3.
        val old = PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 2), 1, 2, 7, Primitives.randomBytes(32), 1_000L)
        disk.upload("$dir/${CloudPaths.packName(1, 2)}", old)
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
        again.sync(disk).run()
        assertTrue(fresh.liveEntries().containsAll(days))
        again.sync(disk).run(forceCompaction = true)
        assertEquals(setOf("$dir/${CloudPaths.packName(1, 3)}"), disk.files("$dir/").keys, "the stale copy goes, 1-3 is sealed whole")
        mom.sync(disk).run()
        assertTrue(mom.store.liveEntries().containsAll(days), "a reader gets all three from the Disk")
    }

    /** Parked Y3 minor: a kept own pack is opened once its key generation reaches the ring; its entries come back. */
    @Test
    fun aKeptPackIsReadOnceItsKeyArrives() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        val k7 = Primitives.randomBytes(32)
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 3)}", PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 3, 7, k7, 1_000L))
        val ring = InMemoryKeyRing(mapOf(0 to GEN0))
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = ring)
        again.sync(disk).run()
        assertEquals(3L, fresh.versionVector()[kid.id])
        assertTrue(fresh.liveEntries().none { it.author == kid.id })
        assertTrue(again.cursor.cursor.ownPacks.getValue(CloudPaths.packName(1, 3)).kept)
        val downloads = disk.downloads.size
        again.sync(disk).run()
        assertEquals(downloads, disk.downloads.size, "not read again while the key is missing")
        ring.put(7, k7)
        again.sync(disk).run()
        assertTrue(fresh.liveEntries().containsAll(days), "the entries are back")
        assertTrue(again.cursor.cursor.ownPacks.values.none { it.kept })
    }

    /** Parked Y3 minor: a kept pack whose key of that generation did not open it waits for another key of it. */
    @Test
    fun aKeptPackThatDidNotOpenUnderOurKeyWaitsForAnotherOne() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        kid.publish(kid.author.summary("2026-10-01", 60_000L))
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 1)}", PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 1, 0, Primitives.randomBytes(32), 1_000L))
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
        again.sync(disk).run()
        assertTrue(again.cursor.cursor.ownPacks.getValue(CloudPaths.packName(1, 1)).kept)
        val downloads = disk.downloads.size
        again.sync(disk).run()
        assertEquals(downloads, disk.downloads.size)
    }

    /** Parked Y3 minor: a file in our folder that is not ours is downloaded once per version, not on every run. */
    @Test
    fun aRejectedOwnFileIsNotDownloadedEveryRun() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 60_000L))
        kid.sync(disk).run()
        val path = "${CloudPaths.deviceDir(kid.store.groupId, kid.id)}/${CloudPaths.packName(2, 5)}"
        disk.upload(path, byteArrayOf(1, 2, 3))
        repeat(3) { kid.sync(disk).run() }
        assertEquals(1, disk.downloads.count { it == path })
        disk.upload(path, byteArrayOf(4, 5, 6, 7)) // a new version is looked at again
        kid.sync(disk).run()
        assertEquals(2, disk.downloads.count { it == path })
    }

    /** Parked Y3 minor: a far name in our folder is told once, not at every run. */
    @Test
    fun aFarOwnNameIsLoggedOnce() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(kid.store.groupId), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 20_000_000)}", byteArrayOf(1, 2, 3))
        val lines = mutableListOf<String>()
        val sync = CloudSync(kid.store, kid.keys, SyncPolicy(kid.id, showOthers = false, today = { TODAY }), disk, kid.ring, kid.cursor, { 1_000L }, log = { lines += it })
        repeat(3) { sync.run() }
        assertEquals(1, lines.count { "too far" in it })
        assertEquals(0L, kid.store.versionVector()[kid.id])
    }

    /** Parked Y3 minor: a full Disk refusing storage.id still lets the owner read the others; the run says StorageFull. */
    @Test
    fun aFullDiskOnTheStorageIdStillReadsTheOthers() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val day = kid.author.summary("2026-10-13", 5)
        kid.publish(day)
        kid.sync(disk).run()
        val full = object : DiskApi by disk {
            override suspend fun upload(path: String, bytes: ByteArray) =
                if (path.endsWith(CloudPaths.STORAGE_ID)) throw DiskException.StorageFull() else disk.upload(path, bytes)
        }
        val sync = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), full, mom.ring, mom.cursor, { 1_000L }, ownedStorageId = ByteArray(16) { 7 })
        val result = sync.run()
        assertIs<CloudResult.StorageFull>(result)
        assertEquals(1, result.received)
        assertTrue(day in mom.store.liveEntries())
    }

    /** Parked Y3 minor: an owned storage id that is not 16 bytes is a wiring error: told once, never a Retry loop. */
    @Test
    fun aMalformedOwnedStorageIdIsIgnored() = runTest {
        val (mom) = family()
        val lines = mutableListOf<String>()
        val sync = CloudSync(mom.store, mom.keys, SyncPolicy(mom.id, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L }, log = { lines += it }, ownedStorageId = ByteArray(5))
        repeat(2) { assertIs<CloudResult.Synced>(sync.run()) }
        assertTrue(disk.files(CloudPaths.storageIdFile(mom.store.groupId)).isEmpty())
        assertEquals(1, lines.count { "storage id" in it })
    }

    /** Review Critical: a cursor change in the lift (a stray file, a far name) never marks unuploaded entries uploaded. */
    @Test
    fun aStrayOwnFileDoesNotSkipEntriesWaitingToUpload() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val first = kid.author.summary("2026-10-01", 60_000L)
        kid.publish(first)
        kid.sync(disk).run()
        val waiting = listOf(kid.author.summary("2026-10-02", 1), kid.author.summary("2026-10-03", 2))
        kid.publish(*waiting.toTypedArray())
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        disk.upload("$dir/${CloudPaths.packName(10, 12)}", byteArrayOf(1, 2, 3)) // rejected: a new REJECTED marker
        disk.upload("$dir/${CloudPaths.packName(1, 20_000_000)}", byteArrayOf(4)) // far: a new FAR marker
        kid.sync(disk).run()
        assertEquals(3L, kid.cursor.cursor.uploadedUpTo)
        mom.sync(disk).run()
        assertTrue(mom.store.liveEntries().containsAll(waiting + first), "the waiting entries reached the Disk")
    }

    /** Review Critical: a kept pack read late does not mark entries signed since then as uploaded. */
    @Test
    fun aKeptPackReadLateDoesNotSkipNewerEntries() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        val days = (1..3).map { kid.author.summary("2026-10-0$it", it * 60_000L) }
        kid.publish(*days.toTypedArray())
        val k7 = Primitives.randomBytes(32)
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 3)}", PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 3, 7, k7, 1_000L))
        val ring = InMemoryKeyRing(mapOf(0 to GEN0))
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = ring)
        again.sync(disk).run()
        val later = EntryAuthor(g, kid.keys, lastSeq = 3).summary("2026-10-04", 4)
        again.publish(later)
        ring.put(7, k7) // the retry and the upload happen in one run
        again.sync(disk).run()
        mom.sync(disk).run()
        assertTrue(later in mom.store.liveEntries(), "seq 4 reached the Disk")
    }

    /** Review Important: a kept pack retried that turns out not to be ours is not downloaded on every run. */
    @Test
    fun aRetriedKeptPackThatIsRejectedIsNotFetchedAgain() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        kid.publish(kid.author.summary("2026-10-01", 60_000L))
        val k7 = Primitives.randomBytes(32)
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        val path = "$dir/${CloudPaths.packName(1, 1)}"
        disk.upload(path, PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 1, 7, k7, 1_000L))
        val ring = InMemoryKeyRing(mapOf(0 to GEN0))
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = ring)
        again.sync(disk).run()
        assertTrue(again.cursor.cursor.ownPacks.getValue(CloudPaths.packName(1, 1)).kept)
        // The retry meets a file that is not ours (planted under the same listed version).
        var fetched = 0
        val wrapped = object : DiskApi by disk {
            override suspend fun download(path: String): ByteArray =
                if (path.endsWith(CloudPaths.packName(1, 1))) byteArrayOf(9).also { fetched++ } else disk.download(path)
        }
        ring.put(7, k7)
        repeat(3) { again.sync(wrapped).run() }
        assertEquals(1, fetched, "rejected once, not fetched on every run")
    }

    /** Sweep review Minor 1: a kept pack pins its generation only while its file is on the Disk. */
    @Test
    fun aKeptPackDeletedFromTheDiskNoLongerKeepsItsKey() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        kid.publish(kid.author.summary("2026-10-01", 60_000L))
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        val path = "$dir/${CloudPaths.packName(1, 1)}"
        disk.upload(path, PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 1, 7, Primitives.randomBytes(32), 1_000L))
        val ring = InMemoryKeyRing(mapOf(0 to GEN0, 7 to Primitives.randomBytes(32))) // another key of 7: KeyMismatch
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val again = Phone("kid", kid.keys, EntryAuthor(g, kid.keys, lastSeq = 0), fresh, isChild = true, ring = ring)
        again.sync(disk).run()
        assertTrue(again.cursor.cursor.ownPacks.getValue(CloudPaths.packName(1, 1)).kept)
        assertTrue(7 in ring.keys(), "pinned while the kept file remains")
        disk.delete(path) // deleted in the Disk UI
        again.sync(disk).run()
        assertEquals(setOf(0), ring.keys().keys)
    }
}
