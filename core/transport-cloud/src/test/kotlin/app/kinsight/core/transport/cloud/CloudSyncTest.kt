package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private class FullDisk(private val inner: DiskApi) : DiskApi by inner {
        override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
    }
}
