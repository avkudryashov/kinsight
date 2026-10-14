package app.kinsight.core.transport.cloud

import kotlinx.coroutines.sync.Mutex
import kotlin.test.assertFalse
import java.security.MessageDigest
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.SyncPolicy
import app.kinsight.core.sync.TODAY
import app.kinsight.core.sync.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CloudSyncTest {
    private val disk = InMemoryDisk()

    @Test
    fun parentReadsChildUsage() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val day = kid.author.summary("2026-10-13", 60_000)
        kid.publish(day)
        assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 0), kid.sync(disk).run())
        assertIs<CloudResult.Synced>(mom.sync(disk).run())
        assertTrue(day in mom.store.liveEntries())
        assertEquals(kid.store.versionVector()[kid.id], mom.store.versionVector()[kid.id])
    }

    @Test
    fun oneSyncWritesOneFile() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        repeat(3) { kid.publish(kid.author.summary("2026-10-0${it + 1}", 1)) }
        kid.sync(disk).run()
        assertEquals(1, disk.files(CloudPaths.deviceDir(kid.store.groupId, kid.id)).size)
    }

    @Test
    fun supersededSeqsAreAccountedThroughTheSignedRange() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-13", 1))
        val newer = kid.author.summary("2026-10-13", 2)
        kid.publish(newer)
        kid.sync(disk).run()
        mom.sync(disk).run()
        assertEquals(2L, mom.store.versionVector()[kid.id])
        assertEquals(listOf(newer), mom.store.liveEntries().filter { it.author == kid.id })
    }

    @Test
    fun secondRunWithNothingNewUploadsNothingAndDownloadsNothing() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(disk).run()
        mom.sync(disk).run()
        kid.sync(disk).run() // reads Mom's pack once
        val before = disk.downloads.size
        assertEquals(CloudResult.Synced(0, 0), kid.sync(disk).run())
        assertEquals(CloudResult.Synced(0, 0), mom.sync(disk).run())
        assertEquals(before, disk.downloads.size)
    }

    @Test
    fun childTakesMembershipAndTheOwnersAccessButNotParentUsage() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val grandma = DeviceKeys.generate()
        val add = mom.author.addMember(grandma.sign.publicKey, grandma.dh.publicKey, "Grandma")
        mom.publish(add, mom.author.storageOwnerEntry(mom.id, ByteArray(16) { 1 }, 1))
        mom.publish(CloudAccessView.issue(mom.author, mom.membership(), 0, GEN0, "AT", expiresAtMs = 9))
        mom.publish(mom.author.summary("2026-10-13", 5))
        mom.sync(disk).run()
        kid.sync(disk).run()
        assertTrue(add in kid.store.membershipEntries())
        assertEquals("AT", CloudAccessView.current(kid.membership(), kid.store.liveEntries(), kid.ring.keys())!!.accessToken)
        assertTrue(kid.store.liveEntries().none { it.body.usage_summary != null && it.author == mom.id })
    }

    @Test
    fun removedDeviceCannotReadNewPacks() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        mom.publish(mom.author.removeMemberEntry(dad.id, lastSeq = 0))
        mom.publish(GroupKey.rotate(mom.author, mom.membership())) // spec §5: the key changes on removal
        mom.sync(disk).run()
        kid.sync(disk).run() // learns generation 1 from the key update next to Mom's pack, then the removal inside it
        assertTrue(1 in kid.ring.keys())
        val after = kid.author.summary("2026-10-13", 7)
        kid.publish(after)
        kid.sync(disk).run()
        val file = disk.files(CloudPaths.deviceDir(kid.store.groupId, kid.id)).values.last()
        assertEquals(1, PackCodec.epochOf(file))
        assertIs<CloudResult.Synced>(dad.sync(disk).run()) // Dad still has a working token and generation 0
        assertTrue(after !in dad.store.liveEntries())
    }

    @Test
    fun foreignPackInWrongFolderIsIgnored() = runTest {
        val (mom, anna, bob) = family(DeviceRole.CHILD, DeviceRole.CHILD)
        anna.publish(anna.author.summary("2026-10-13", 1))
        anna.sync(disk).run()
        val (path, bytes) = disk.files(CloudPaths.deviceDir(anna.store.groupId, anna.id)).entries.single()
        disk.mkdir(CloudPaths.deviceDir(bob.store.groupId, bob.id))
        disk.upload("${CloudPaths.deviceDir(bob.store.groupId, bob.id)}/${path.substringAfterLast('/')}", bytes)
        disk.deleteTree(CloudPaths.deviceDir(anna.store.groupId, anna.id))
        mom.sync(disk).run()
        assertEquals(0L, mom.store.versionVector()[anna.id])
        assertEquals(0L, mom.store.versionVector()[bob.id])
    }

    @Test
    fun interruptedUploadResumes() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        repeat(4) { kid.publish(kid.author.summary("2026-10-0${it + 1}", it + 1L)) }
        disk.failUploadsAfter = 1
        assertIs<CloudResult.Retry>(kid.sync(disk, maxPackEntries = 2).run())
        assertEquals(2L, kid.cursor.cursor.uploadedUpTo)
        disk.failUploadsAfter = null
        assertEquals(CloudResult.Synced(1, 0), kid.sync(disk, maxPackEntries = 2).run())
        mom.sync(disk).run()
        assertEquals(4L, mom.store.versionVector()[kid.id])
    }

    @Test
    fun packOfAMemberAddedByAnotherParentIsReadInTheSameRun() = runTest {
        val (mom, dad) = family(DeviceRole.PARENT)
        val grant = mom.author.grantAdminEntry(dad.id)
        mom.publish(grant)
        dad.store.storeMembership(listOf(grant)) // Dad learns he is an admin (over Bluetooth in the app)
        val kidKeys = DeviceKeys.generate()
        dad.publish(dad.author.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD))
        dad.sync(disk).run()
        val kidStore = InMemoryEntryStore(mom.store.groupId).also { it.storeMembership(dad.store.membershipEntries()) }
        val kid = Phone("kid", kidKeys, EntryAuthor(mom.store.groupId, kidKeys, lastSeq = 0), kidStore, isChild = true, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
        val day = kid.author.summary("2026-10-13", 9)
        kid.publish(day)
        kid.sync(disk).run()
        // Mom learns the kid's AddMember only from Dad's pack, in the run that first lists the kid's pack.
        mom.sync(disk).run()
        assertTrue(day in mom.store.liveEntries())
    }

    @Test
    fun withoutAnyGroupKeyNothingIsWritten() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.ring.retain(emptySet())
        kid.publish(kid.author.summary("2026-10-13", 1))
        assertEquals(CloudResult.NoGroupKey, kid.sync(disk).run())
        assertTrue(disk.files("app:").isEmpty())
    }

    @Test
    fun theGenerationOfTheCurrentAccessSurvivesRotation() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        mom.publish(mom.author.storageOwnerEntry(mom.id, ByteArray(16) { 1 }, 1))
        mom.publish(CloudAccessView.issue(mom.author, mom.membership(), 0, GEN0, "AT", expiresAtMs = 9))
        mom.publish(mom.author.removeMemberEntry(dad.id, lastSeq = 0))
        mom.publish(GroupKey.rotate(mom.author, mom.membership())) // the access is not re-issued under generation 1
        mom.sync(disk).run()
        kid.sync(disk).run()
        assertTrue(1 in kid.ring.keys())
        kid.publish(kid.author.summary("2026-10-13", 3)) // only generation 1 packs from here on
        kid.sync(disk).run()
        mom.sync(disk).run()
        kid.sync(disk).run()
        assertTrue(0 in kid.ring.keys())
        assertEquals("AT", CloudAccessView.current(kid.membership(), kid.store.liveEntries(), kid.ring.keys())!!.accessToken)
    }

    @Test
    fun packOfAnUnknownAuthorIsRetriedWhenItsMembershipArrives() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val grandmaKeys = DeviceKeys.generate()
        mom.publish(mom.author.addMember(grandmaKeys.sign.publicKey, grandmaKeys.dh.publicKey, "Grandma"))
        val all = mom.store.membershipEntries()
        val kidAdd = all.single { it.seq == 2L && it.author == mom.id }
        val gen1 = ByteArray(32) { 7 }
        kid.ring.put(1, gen1)
        kid.ring.retain(setOf(1))
        val day = kid.author.summary("2026-10-13", 4)
        kid.publish(day)
        kid.sync(disk).run()
        val store = InMemoryEntryStore(mom.store.groupId).also { it.storeMembership(all - kidAdd) }
        val grandma = Phone("grandma", grandmaKeys, EntryAuthor(mom.store.groupId, grandmaKeys, lastSeq = 0), store, isChild = false, ring = InMemoryKeyRing(mapOf(1 to gen1)))
        grandma.sync(disk).run()
        assertTrue(day !in grandma.store.liveEntries())
        assertEquals(1, grandma.cursor.cursor.deferred.size)
        assertTrue(1 in grandma.ring.keys()) // a deferred pack still pins its generation
        grandma.store.storeMembership(listOf(kidAdd))
        val before = disk.downloads.size
        grandma.sync(disk).run()
        assertTrue(disk.downloads.size > before)
        assertTrue(day in grandma.store.liveEntries())
        assertTrue(grandma.cursor.cursor.deferred.isEmpty())
    }

    @Test
    fun fullStorageStillReadsTheOthers() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val day = kid.author.summary("2026-10-13", 5)
        kid.publish(day)
        kid.sync(disk).run()
        mom.publish(mom.author.summary("2026-10-12", 1))
        assertEquals(CloudResult.StorageFull, mom.sync(FullDisk(disk)).run())
        assertTrue(day in mom.store.liveEntries())
        assertEquals(0L, mom.cursor.cursor.uploadedUpTo)
    }

    @Test
    fun localStoreFailureAbortsTheRunAndTheNextRunIngests() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val day = kid.author.summary("2026-10-13", 5)
        kid.publish(day)
        kid.sync(disk).run()
        val flaky = FlakyStore(mom.store)
        val sync = CloudSync(flaky, mom.keys, SyncPolicy(mom.id, showOthers = true, today = { TODAY }), disk, mom.ring, mom.cursor, { 1_000L })
        assertIs<CloudResult.Retry>(sync.run())
        assertTrue(mom.cursor.cursor.seen.isEmpty())
        assertTrue(day !in mom.store.liveEntries())
        assertIs<CloudResult.Synced>(sync.run())
        assertTrue(day in mom.store.liveEntries())
        assertEquals(1, mom.cursor.cursor.seen.size)
    }

    @Test
    fun renamedPackIsRejected() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 1), kid.author.summary("2026-10-02", 2))
        kid.sync(disk).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        val (path, bytes) = disk.files(dir).entries.single()
        disk.delete(path)
        disk.upload("$dir/${CloudPaths.packName(1, 1)}", bytes)
        mom.sync(disk).run()
        assertEquals(0L, mom.store.versionVector()[kid.id])
        assertEquals(listOf(-1), mom.cursor.cursor.seen.values.toList())
    }

    @Test
    fun runsOfOneInstanceDoNotOverlap() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-13", 1))
        val gated = GatedDisk(disk)
        val sync = kid.sync(gated)
        val a = launch { sync.run() }
        val b = launch { sync.run() }
        yield() // both runs start: one waits at the gate, the other at the mutex
        gated.gate.complete(Unit)
        a.join()
        b.join()
        assertEquals(1, gated.maxInside)
    }

    @Test
    fun theAccessGenerationIsFoundWithoutDecodingTheWholeDatabase() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        mom.publish(mom.author.storageOwnerEntry(mom.id, ByteArray(16) { 1 }, 1))
        mom.publish(CloudAccessView.issue(mom.author, mom.membership(), 0, GEN0, "AT", expiresAtMs = 9))
        mom.sync(disk).run()
        val store = NoFullScanStore(kid.store)
        val sync = CloudSync(store, kid.keys, SyncPolicy(kid.id, showOthers = false, today = { TODAY }), disk, kid.ring, kid.cursor, { 1_000L })
        assertIs<CloudResult.Synced>(sync.run())
        assertIs<CloudResult.Synced>(sync.run())
        assertEquals(0, store.fullScans)
    }

    /** Counts liveEntries calls: on Room each one decodes every row of the database. */
    private class NoFullScanStore(private val inner: InMemoryEntryStore) : EntryStore by inner {
        var fullScans = 0

        override suspend fun liveEntries(): List<DecodedEntry> {
            fullScans++
            return inner.liveEntries()
        }
    }

    /** The first list call of every run waits for [gate]; [maxInside] is the most runs seen inside list at once. */
    private class GatedDisk(private val inner: DiskApi) : DiskApi by inner {
        val gate = CompletableDeferred<Unit>()
        private var inside = 0
        var maxInside = 0

        override suspend fun list(path: String): List<DiskItem> {
            inside++
            maxInside = maxOf(maxInside, inside)
            try {
                gate.await()
                return inner.list(path)
            } finally {
                inside--
            }
        }
    }

    /** Fails the first applyBatch, as a full or locked local database would. */
    private class FlakyStore(private val inner: InMemoryEntryStore) : EntryStore by inner {
        private var failed = false

        override suspend fun applyBatch(entries: List<DecodedEntry>, seen: Map<DeviceId, Set<Long>>) {
            if (!failed) {
                failed = true
                throw IllegalStateException("database is locked")
            }
            inner.applyBatch(entries, seen)
        }
    }

    private class FullDisk(private val inner: DiskApi) : DiskApi by inner {
        override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
    }

    @Test
    fun aResealedPackOfTheSameSizeIsANewVersionWithoutSha256() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val md5Only = Md5OnlyDisk(disk)
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(md5Only).run()
        mom.sync(md5Only).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        val before = disk.files(dir).values.single()
        kid.sync(md5Only).run(forceCompaction = true) // the same range sealed again: same name, same size, new bytes
        val after = disk.files(dir).values.single()
        assertEquals(before.size, after.size)
        assertFalse(before.contentEquals(after))
        mom.sync(md5Only).run()
        assertEquals(listOf("${kid.id.hex}/${CloudPaths.packName(1, 1)}/md5:${md5(after)}"), mom.cursor.cursor.seen.keys.toList())
    }

    @Test
    fun aMismatchedPackIsNotDownloadedAgainInTheSameRun() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        val grant = mom.author.grantAdminEntry(dad.id)
        mom.publish(grant)
        dad.store.storeMembership(listOf(grant))
        // Grandma's folder must come before Dad's in the run: then a second round exists, the one that used to re-download.
        val grandmaKeys = generateSequence { DeviceKeys.generate() }.first { it.deviceId < dad.id }
        dad.publish(dad.author.addMember(grandmaKeys.sign.publicKey, grandmaKeys.dh.publicKey, "Grandma"))
        dad.sync(disk).run()
        val grandmaStore = InMemoryEntryStore(mom.store.groupId).also { it.storeMembership(dad.store.membershipEntries()) }
        val grandma = Phone("grandma", grandmaKeys, EntryAuthor(mom.store.groupId, grandmaKeys, lastSeq = 0), grandmaStore, isChild = false, ring = InMemoryKeyRing(mapOf(0 to GEN0)))
        grandma.publish(grandma.author.summary("2026-10-13", 2))
        grandma.sync(disk).run()
        kid.ring.put(0, ByteArray(32) { 9 }) // the kid seals with another key of generation 0
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(disk).run()
        mom.sync(disk).run()
        val kidPack = "${CloudPaths.deviceDir(kid.store.groupId, kid.id)}/${CloudPaths.packName(1, 1)}"
        assertEquals(1, disk.downloads.count { it == kidPack })
        assertTrue(mom.store.liveEntries().any { it.author == grandma.id }, "the second round still ran")
    }

    @Test
    fun aPlantedFolderIsNotDownloadedEveryRun() = runTest {
        val (mom, _) = family(DeviceRole.CHILD)
        plantStrangerPack(mom)
        mom.sync(disk).run()
        val before = disk.downloads.size
        mom.sync(disk).run()
        mom.sync(disk).run()
        assertEquals(before, disk.downloads.size, "an unknown author is tried again only after membership changed")
        mom.publish(mom.author.addMember(DeviceKeys.generate().sign.publicKey, DeviceKeys.generate().dh.publicKey, "Grandma"))
        mom.sync(disk).run()
        assertEquals(before + 1, disk.downloads.size, "membership changed: one more try")
    }

    @Test
    fun aPlantedFolderDoesNotKeepAnOldKey() = runTest {
        val (mom, _) = family(DeviceRole.CHILD)
        plantStrangerPack(mom) // sealed with generation 0
        mom.publish(GroupKey.rotate(mom.author, mom.membership()))
        mom.sync(disk).run()
        assertEquals(setOf(1), mom.ring.keys().keys)
    }

    /** A token holder that is no member writes a folder of its own, sealed with generation 0. */
    private suspend fun plantStrangerPack(mom: Phone) {
        val stranger = DeviceKeys.generate()
        val g = mom.store.groupId
        val entry = EntryAuthor(g, stranger, lastSeq = 0).summary("2026-10-13", 1)
        val bytes = PackCodec.seal(stranger, g, listOf(entry), 1, 1, 0, GEN0, 1_000L)
        val dir = CloudPaths.deviceDir(g, stranger.deviceId)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 1)}", bytes)
    }

    /** Parked from Task 1: without any version from the Disk, every run gets its own key, even under a fixed clock. */
    @Test
    fun aPackWithoutAnyVersionIsReadAgainOnEveryRun() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val bare = BareDisk(disk)
        kid.publish(kid.author.summary("2026-10-13", 1))
        kid.sync(bare).run()
        mom.sync(bare).run()
        val first = mom.cursor.cursor.seen.keys.toList()
        mom.sync(bare).run()
        val second = mom.cursor.cursor.seen.keys.toList()
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertNotEquals(first, second, "the same clock must not make the second run trust the first one's read")
    }

    /** A listing with no sha256, md5 or modified time (never seen from Yandex). */
    private class BareDisk(private val inner: DiskApi) : DiskApi by inner {
        override suspend fun list(path: String): List<DiskItem> =
            inner.list(path).map { DiskItem(it.name, it.isDir, it.size, sha256 = null, md5 = null, modified = null) }
    }

    /** The listing as a Disk without sha256 reports it: md5 and modified only. */
    private class Md5OnlyDisk(private val inner: DiskApi) : DiskApi by inner {
        override suspend fun list(path: String): List<DiskItem> =
            inner.list(path).map { DiskItem(it.name, it.isDir, it.size, sha256 = null, md5 = it.md5, modified = it.modified) }
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).toHex()

    @Test
    fun instancesSharingTheProcessLockDoNotOverlap() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-13", 1))
        val gated = GatedDisk(disk)
        val lock = Mutex()
        fun instance() = CloudSync(
            kid.store, kid.keys, SyncPolicy(kid.id, showOthers = false, today = { TODAY }), gated, kid.ring, kid.cursor, { 1_000L },
            running = lock,
        )
        val a = launch { instance().run() }
        val b = launch { instance().run() }
        yield()
        gated.gate.complete(Unit)
        a.join()
        b.join()
        assertEquals(1, gated.maxInside)
    }
}
