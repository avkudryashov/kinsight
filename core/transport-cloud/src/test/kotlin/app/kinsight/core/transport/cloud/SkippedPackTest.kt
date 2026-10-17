package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 14: one pack the Disk will not hand out (a storage 500, a dropped read, bytes damaged on the way) is skipped for
 * this run; the run goes on with the other packs and this phone's own uploads, and the pack is read again later.
 */
class SkippedPackTest {
    private val disk = InMemoryDisk()

    /** [disk], except that a download whose path ends with one of [broken] fails with what [failure] gives. */
    private class BrokenPacks(
        private val inner: DiskApi,
        val broken: MutableSet<String> = mutableSetOf(),
        private val failure: (String) -> IOException = { DiskException.Server(500, "storage get $it") },
    ) : DiskApi by inner {
        var attempts = 0

        override suspend fun download(path: String): ByteArray {
            if (broken.any { path.endsWith(it) }) {
                attempts++
                throw failure(path)
            }
            return inner.download(path)
        }
    }

    private fun packOf(phone: Phone, from: Long, to: Long) = "${phone.id.hex}/${CloudPaths.packName(from, to)}"

    @Test
    fun aPackTheStorageWillNotGiveIsSkippedAndTheRestOfTheRunGoesOn() = runTest {
        val (mom, dad, kid) = family(DeviceRole.PARENT, DeviceRole.CHILD)
        val kidDay = kid.author.summary("2026-10-17", 60_000)
        kid.publish(kidDay)
        kid.sync(disk).run()
        val dadDay = dad.author.summary("2026-10-17", 30_000)
        dad.publish(dadDay)
        dad.sync(disk).run()
        mom.publish(mom.author.summary("2026-10-17", 1_000))
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        val result = mom.sync(broken).run()
        assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 1, skipped = 1), result)
        assertTrue(dadDay in mom.store.liveEntries(), "the other author's pack is applied")
        assertTrue(kidDay !in mom.store.liveEntries())
        assertEquals(1, disk.files(CloudPaths.deviceDir(mom.store.groupId, mom.id)).size, "own pack uploaded")
        assertTrue(mom.cursor.cursor.seen.keys.none { it.startsWith(kid.id.hex) }, "the skipped pack is not marked seen")
        // Healed: the next run reads it.
        assertEquals(CloudResult.Synced(uploadedPacks = 0, received = 1, skipped = 0), mom.sync(disk).run())
        assertTrue(kidDay in mom.store.liveEntries())
        assertEquals(kid.store.versionVector()[kid.id], mom.store.versionVector()[kid.id])
        assertTrue(mom.cursor.cursor.failing.isEmpty())
    }

    @Test
    fun aDroppedReadOfOnePackIsSkippedToo() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 60_000))
        kid.sync(disk).run()
        mom.publish(mom.author.summary("2026-10-17", 1_000))
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1))) { IOException("connection reset") }
        assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 0, skipped = 1), mom.sync(broken).run())
        assertIs<CloudResult.Synced>(mom.sync(disk).run())
        assertEquals(1L, mom.store.versionVector()[kid.id])
    }

    /** Later packs of the author are applied meanwhile; once the gap heals, the author's signed ranges complete it. */
    @Test
    fun theAuthorsLogCompletesOnceTheSkippedPackHeals() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-16", 1))
        kid.sync(disk).run() // pack 1-1
        kid.publish(kid.author.summary("2026-10-17", 1))
        val newest = kid.author.summary("2026-10-17", 2) // supersedes seq 2: pack 2-3 holds seq 3 only
        kid.publish(newest)
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        assertEquals(1, (mom.sync(broken).run() as CloudResult.Synced).skipped)
        assertTrue(newest in mom.store.liveEntries(), "the later pack is applied")
        assertEquals(0L, mom.store.versionVector()[kid.id])
        mom.sync(disk).run()
        assertEquals(3L, mom.store.versionVector()[kid.id])
    }

    @Test
    fun aPackThatKeepsFailingIsTriedOnlyHourlyAfterThreeFailures() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val kidDay = kid.author.summary("2026-10-17", 60_000)
        kid.publish(kidDay)
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        var now = 1_000L
        repeat(3) {
            assertEquals(1, (mom.sync(broken, now = { now }).run() as CloudResult.Synced).skipped)
            now += 60_000
        }
        assertEquals(3, broken.attempts)
        // In backoff: not tried, so nothing was skipped by this run.
        assertEquals(CloudResult.Synced(0, 0, skipped = 0), mom.sync(broken, now = { now }).run())
        assertEquals(3, broken.attempts)
        val lastFailure = now - 60_000
        now = lastFailure + CloudSync.FAILED_PACK_RETRY_MS - 1
        mom.sync(broken, now = { now }).run()
        assertEquals(3, broken.attempts, "not before an hour passed")
        now = lastFailure + CloudSync.FAILED_PACK_RETRY_MS
        assertEquals(1, (mom.sync(broken, now = { now }).run() as CloudResult.Synced).skipped)
        assertEquals(4, broken.attempts)
        broken.broken.clear()
        now += 60_000
        assertEquals(0, (mom.sync(broken, now = { now }).run() as CloudResult.Synced).skipped, "still in backoff")
        assertTrue(kidDay !in mom.store.liveEntries())
        now += CloudSync.FAILED_PACK_RETRY_MS
        mom.sync(broken, now = { now }).run()
        assertTrue(kidDay in mom.store.liveEntries(), "read once it healed")
        assertTrue(mom.cursor.cursor.failing.isEmpty())
    }

    @Test
    fun anUnauthorizedPackDownloadStillFailsTheWholeRun() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 60_000))
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1))) { DiskException.Unauthorized() }
        assertEquals(CloudResult.Unauthorized, mom.sync(broken).run())
    }

    @Test
    fun aRateLimitedPackDownloadStillRetriesTheWholeRun() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 60_000))
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1))) { DiskException.RateLimited(null) }
        assertIs<CloudResult.Retry>(mom.sync(broken).run())
        assertTrue(mom.cursor.cursor.failing.isEmpty())
    }

    @Test
    fun aNetworkDownAtTheFirstRequestStillRetriesTheWholeRun() = runTest {
        val (mom, _) = family(DeviceRole.CHILD)
        val down = object : DiskApi by disk {
            override suspend fun list(path: String): List<DiskItem> = throw IOException("network is unreachable")
        }
        assertIs<CloudResult.Retry>(mom.sync(down).run())
    }

    /** Bytes damaged on the way (they do not match the listed sha256) are read again; damaged at rest stay rejected. */
    @Test
    fun bytesDamagedOnTheWayAreReadAgain() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val kidDay = kid.author.summary("2026-10-17", 60_000)
        kid.publish(kidDay)
        kid.sync(disk).run()
        val damaged = object : DiskApi by disk {
            override suspend fun download(path: String): ByteArray =
                disk.download(path).also { if (path.endsWith(packOf(kid, 1, 1))) it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        }
        assertEquals(1, (mom.sync(damaged).run() as CloudResult.Synced).skipped)
        assertTrue(mom.cursor.cursor.seen.isEmpty(), "not rejected for good")
        mom.sync(disk).run()
        assertTrue(kidDay in mom.store.liveEntries())
    }

    /** A kept own pack (A-4) retried once its key arrives may fail on the Disk too: the own upload still happens. */
    @Test
    fun aKeptOwnPackTheStorageWillNotGiveDoesNotStopTheUpload() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = kid.store.groupId
        kid.publish(kid.author.summary("2026-10-01", 60_000L))
        val k7 = Primitives.randomBytes(32)
        val dir = CloudPaths.deviceDir(g, kid.id)
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), dir)) disk.mkdir(d)
        disk.upload("$dir/${CloudPaths.packName(1, 1)}", PackCodec.seal(kid.keys, g, kid.store.entriesAfter(kid.id, 0, 10), 1, 1, 7, k7, 1_000L))
        val ring = InMemoryKeyRing(mapOf(0 to GEN0))
        val fresh = InMemoryEntryStore(g).also { it.storeMembership(kid.store.membershipEntries()) }
        val author = EntryAuthor(g, kid.keys, lastSeq = 0)
        val again = Phone("kid", kid.keys, author, fresh, isChild = true, ring = ring)
        again.sync(disk).run()
        assertTrue(again.cursor.cursor.ownPacks.getValue(CloudPaths.packName(1, 1)).kept)
        ring.put(7, k7)
        val later = EntryAuthor(g, kid.keys, lastSeq = 1).summary("2026-10-02", 60_000L)
        fresh.applyBatch(listOf(later))
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        val result = again.sync(broken).run()
        assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 0, skipped = 1), result)
        mom.sync(disk).run()
        assertTrue(later in mom.store.liveEntries(), "seq 2 reached the Disk")
    }
}
