package app.kinsight.core.transport.cloud

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PackFile
import app.kinsight.core.model.proto.PackHeader
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {
    private val disk = InMemoryDisk()
    private val day = CloudSync.COMPACTION_INTERVAL_MS

    @Test
    fun ownPacksBecomeOneOnceADay() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        var now = 10L
        repeat(3) {
            kid.publish(kid.author.summary("2026-10-0${it + 1}", 1))
            kid.sync(disk, { now }).run()
        }
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        assertEquals(3, disk.files(dir).size) // compaction is first due a day after lastCompactionMs = 0
        now += day
        kid.sync(disk, { now }).run()
        assertEquals(listOf("$dir/${CloudPaths.packName(1, 3)}"), disk.files(dir).keys.toList())
        mom.sync(disk).run()
        assertEquals(3L, mom.store.versionVector()[kid.id])
    }

    @Test
    fun wipedFolderIsReuploaded() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        repeat(2) { kid.publish(kid.author.summary("2026-10-0${it + 1}", 1)) }
        kid.sync(disk).run()
        disk.deleteTree(CloudPaths.root(kid.store.groupId))
        kid.sync(disk).run()
        assertEquals(listOf(CloudPaths.packName(1, 2)), disk.files(CloudPaths.deviceDir(kid.store.groupId, kid.id)).keys.map { it.substringAfterLast('/') })
        mom.sync(disk).run()
        assertEquals(2L, mom.store.versionVector()[kid.id])
    }

    @Test
    fun rotationReencryptsOwnPacksAtTheNextRun() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 1))
        kid.sync(disk).run()
        kid.publish(kid.author.summary("2026-10-02", 1))
        kid.sync(disk).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        assertTrue(disk.files(dir).values.all { PackCodec.epochOf(it) == 0 })
        mom.publish(mom.author.removeMemberEntry(dad.id, lastSeq = 0))
        mom.publish(GroupKey.rotate(mom.author, mom.membership()))
        mom.sync(disk).run()
        kid.sync(disk).run() // learns generation 1 at download; re-encrypts at the next run
        kid.sync(disk).run()
        assertEquals(listOf(1), disk.files(dir).values.map { PackCodec.epochOf(it) })
        dad.sync(disk).run()
        assertEquals(0L, dad.store.versionVector()[kid.id])
    }

    @Test
    fun newMemberReadsHistoryAfterReencryption() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 1))
        kid.sync(disk).run()
        mom.publish(GroupKey.rotate(mom.author, mom.membership()))
        val grandmaKeys = DeviceKeys.generate()
        mom.publish(mom.author.addMember(grandmaKeys.sign.publicKey, grandmaKeys.dh.publicKey, "Grandma"))
        mom.sync(disk).run()
        // Pairing hands Grandma the current generation only (Y2).
        val (epoch, key) = GroupKey.open(mom.keys, mom.membership())!!
        val store = InMemoryEntryStore(mom.store.groupId).also { it.storeMembership(mom.store.membershipEntries()) }
        val grandma = Phone("grandma", grandmaKeys, EntryAuthor(mom.store.groupId, grandmaKeys, lastSeq = 0), store, isChild = false, ring = InMemoryKeyRing(mapOf(epoch to key)))
        grandma.sync(disk).run()
        assertEquals(0L, grandma.store.versionVector()[kid.id]) // still sealed with generation 0
        kid.sync(disk).run()
        kid.sync(disk).run() // re-encrypted with generation 1
        grandma.sync(disk).run()
        assertEquals(1L, grandma.store.versionVector()[kid.id])
    }

    @Test
    fun previousKeyIsDroppedOnceNoFileUsesIt() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 1))
        kid.sync(disk).run()
        mom.publish(GroupKey.rotate(mom.author, mom.membership()))
        mom.sync(disk).run()
        assertEquals(setOf(0, 1), mom.ring.keys().keys) // the kid's file is still generation 0
        kid.sync(disk).run()
        kid.sync(disk).run()
        mom.sync(disk).run()
        assertEquals(setOf(1), mom.ring.keys().keys)
    }

    @Test
    fun forcedCompactionReencryptsEvenASinglePack() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-01", 1))
        kid.sync(disk).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        val before = disk.files(dir).values.single()
        kid.sync(disk, { 5_000L }).run(forceCompaction = true)
        val after = disk.files(dir).values.single()
        assertTrue(!before.contentEquals(after))
        assertEquals(5_000L, PackHeader.ADAPTER.decode(PackFile.ADAPTER.decode(after).header_).created_at_ms)
    }

    @Test
    fun alreadyCompactLogIsNotResealedEveryDay() = runTest {
        val (_, kid) = family(DeviceRole.CHILD)
        var now = 10L
        repeat(4) { kid.publish(kid.author.summary("2026-10-0${it + 1}", 1)) }
        kid.sync(disk, { now }, maxPackEntries = 2).run()
        val dir = CloudPaths.deviceDir(kid.store.groupId, kid.id)
        assertEquals(2, disk.files(dir).size)
        now += day
        kid.sync(disk, { now }, maxPackEntries = 2).run()
        val before = disk.files(dir)
        now += day
        kid.sync(disk, { now }, maxPackEntries = 2).run()
        val after = disk.files(dir)
        assertEquals(before.keys, after.keys)
        assertTrue(before.all { (k, v) -> v.contentEquals(after.getValue(k)) })
        assertEquals(now, kid.cursor.cursor.lastCompactionMs)
    }

    @Test
    fun fullStorageDuringCompactionStillReadsTheOthers() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        repeat(3) {
            mom.publish(mom.author.summary("2026-10-0${it + 1}", 1))
            mom.sync(disk, { 10L }).run()
        }
        val dir = CloudPaths.deviceDir(mom.store.groupId, mom.id)
        val before = disk.files(dir)
        val kidDay = kid.author.summary("2026-10-09", 7)
        kid.publish(kidDay)
        kid.sync(disk, { 10L }).run()
        val lastCompaction = mom.cursor.cursor.lastCompactionMs
        assertEquals(CloudResult.StorageFull, mom.sync(FullDisk(disk), { 10L + day }).run())
        assertTrue(kidDay in mom.store.liveEntries())
        assertEquals(before.keys, disk.files(dir).keys)
        assertEquals(lastCompaction, mom.cursor.cursor.lastCompactionMs)
    }

    private class FullDisk(private val inner: DiskApi) : DiskApi by inner {
        override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
    }
}
