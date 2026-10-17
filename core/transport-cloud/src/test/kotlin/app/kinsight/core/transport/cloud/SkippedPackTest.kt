package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Location
import app.kinsight.core.sync.InMemoryEntryStore
import app.kinsight.core.sync.categoryOverride
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 14: one pack the Disk will not hand out (a storage 500, a dropped read, bytes damaged on the way) is skipped for
 * this run; the run goes on with the other packs and this phone's own uploads, and the pack is read again later, with an
 * exponential backoff per pack (5, 10, 20, 40, then 60 minutes, ±10 %); a network drop never counts.
 */
class SkippedPackTest {
    private val disk = InMemoryDisk()
    private val minute = 60_000L

    /** [disk], except that a download whose path ends with one of [broken] fails with what [failure] gives. */
    private class BrokenPacks(
        private val inner: DiskApi,
        val broken: MutableSet<String> = mutableSetOf(),
        private val failure: (String) -> Exception = { DiskException.Server(500, "storage get $it") },
    ) : DiskApi by inner {
        var attempts = 0
        val tried = mutableListOf<String>()

        override suspend fun download(path: String): ByteArray {
            tried += path
            if (broken.any { path.endsWith(it) }) {
                attempts++
                throw failure(path)
            }
            return inner.download(path)
        }
    }

    private fun packOf(phone: Phone, from: Long, to: Long) = "${phone.id.hex}/${CloudPaths.packName(from, to)}"

    private fun CloudResult.synced() = assertIs<CloudResult.Synced>(this)

    /** The wait set by the last failure of the only failing pack of [phone], from [now]. */
    private fun waitOf(phone: Phone, now: Long) = phone.cursor.cursor.failing.values.single().nextAtMs - now

    private fun assertWait(expected: Long, actual: Long) =
        assertTrue(actual in (expected * 0.9).toLong()..(expected * 1.1).toLong(), "wait $actual for $expected")

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
        var now = 1_000L
        val result = mom.sync(broken, now = { now }).run().synced()
        assertEquals(Triple(1, 1, 1), Triple(result.uploadedPacks, result.received, result.skipped))
        assertWait(CloudSync.FIRST_RETRY_MS, result.retryAtMs!! - now)
        assertTrue(dadDay in mom.store.liveEntries(), "the other author's pack is applied")
        assertTrue(kidDay !in mom.store.liveEntries())
        assertEquals(1, disk.files(CloudPaths.deviceDir(mom.store.groupId, mom.id)).size, "own pack uploaded")
        assertTrue(mom.cursor.cursor.seen.keys.none { it.startsWith(kid.id.hex) }, "the skipped pack is not marked seen")
        // Healed: read once its wait is over.
        now += 6 * minute
        val healed = mom.sync(disk, now = { now }).run().synced()
        assertEquals(Triple(0, 1, 0), Triple(healed.uploadedPacks, healed.received, healed.skipped))
        assertNull(healed.retryAtMs)
        assertTrue(kidDay in mom.store.liveEntries())
        assertEquals(kid.store.versionVector()[kid.id], mom.store.versionVector()[kid.id])
        assertTrue(mom.cursor.cursor.failing.isEmpty())
    }

    /** Like ImagePullBackOff: 5, 10, 20, 40, 60, 60 minutes, each within ±10 %; nothing is tried before its time. */
    @Test
    fun aPackThatKeepsFailingWaitsLongerEachTimeUpToAnHour() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val kidDay = kid.author.summary("2026-10-17", 60_000)
        kid.publish(kidDay)
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        var now = 1_000L
        for ((i, step) in listOf(5L, 10, 20, 40, 60, 60).withIndex()) {
            val r = mom.sync(broken, now = { now }).run().synced()
            assertEquals(1, r.skipped)
            assertEquals(i + 1, broken.attempts)
            val wait = waitOf(mom, now)
            assertWait(step * minute, wait)
            assertEquals(now + wait, r.retryAtMs, "the follow-up comes when the pack is due")
            now += wait - 1
            val early = mom.sync(broken, now = { now }).run().synced()
            assertEquals(i + 1, broken.attempts, "not before its time")
            assertEquals(0, early.skipped)
            assertEquals(now + 1, early.retryAtMs)
            now += 1
        }
        // The first good download forgets the failures: a later failure starts the ladder again at 5 minutes.
        broken.broken.clear()
        mom.sync(broken, now = { now }).run()
        assertTrue(kidDay in mom.store.liveEntries())
        assertTrue(mom.cursor.cursor.failing.isEmpty())
        kid.publish(kid.author.summary("2026-10-18", 1))
        kid.sync(disk).run()
        broken.broken += packOf(kid, 2, 2)
        mom.sync(broken, now = { now }).run()
        assertWait(5 * minute, waitOf(mom, now))
    }

    /**
     * A read that drops without an HTTP status (the network went away) is never counted against the pack and ends the
     * downloads of this run; the run is synced and asks for a follow-up a few minutes later. A child's location pack read
     * over a flaky network is therefore read as soon as the network is back, never held back by a backoff.
     */
    @Test
    fun aNetworkDropIsNotCountedAndEndsTheDownloadsOfTheRun() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val now = System.currentTimeMillis() // the receiver's SyncPolicy reads the real clock for LOCATION
        val clock = { now }
        kid.publish(kid.author.summary("2026-10-16", 1))
        kid.sync(disk, clock).run() // pack 1-1
        val point = kid.author.locationEntry(404_000_000, -37_000_000, 20, now, Location.Provider.GPS)
        kid.publish(point)
        kid.sync(disk, clock).run() // pack 2-2
        kid.publish(kid.author.summary("2026-10-17", 1))
        kid.sync(disk, clock).run() // pack 3-3
        val flaky = BrokenPacks(disk, mutableSetOf(packOf(kid, 2, 2))) { IOException("connection reset") }
        repeat(4) {
            val r = mom.sync(flaky, clock).run().synced()
            assertEquals(1, r.skipped)
            assertEquals(now + CloudSync.FIRST_RETRY_MS, r.retryAtMs)
        }
        assertTrue(flaky.tried.none { it.endsWith(packOf(kid, 3, 3)) }, "no download after the drop")
        assertTrue(mom.cursor.cursor.failing.isEmpty(), "a drop never counts")
        assertTrue(point !in mom.store.liveEntries())
        flaky.broken.clear()
        val back = mom.sync(flaky, clock).run().synced()
        assertEquals(0, back.skipped)
        assertTrue(point in mom.store.liveEntries(), "read at once when the network is back")
        assertEquals(3L, mom.store.versionVector()[kid.id])
    }

    /** Review Important 1: a deferred pack (its key did not open it) that keeps failing on the Disk backs off too. */
    @Test
    fun aDeferredPackThatKeepsFailingBacksOff() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val kidKey = ByteArray(32) { 9 }
        kid.ring.put(0, kidKey) // the kid seals with another key of generation 0
        kid.publish(kid.author.summary("2026-10-17", 1))
        kid.sync(disk).run()
        var now = 1_000L
        mom.sync(disk, now = { now }).run()
        assertEquals(1, mom.cursor.cursor.deferred.size)
        mom.ring.put(0, kidKey) // another key of the generation: the deferred pack may open now
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        repeat(4) {
            mom.sync(broken, now = { now }).run()
            now += minute
        }
        assertEquals(1, broken.attempts, "tried once, then it waits its 5 minutes")
        assertEquals(1, mom.cursor.cursor.failing.values.single().count)
        assertEquals(1, mom.cursor.cursor.deferred.size)
    }

    /** Review Important 1: a covered pack skipped unread, read once a key makes its generation matter, backs off too. */
    @Test
    fun aCoveredUnreadPackThatKeepsFailingBacksOff() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 1))
        kid.sync(disk).run()
        mom.store.applyBatch(kid.store.entriesAfter(kid.id, 0, 10)) // over Bluetooth: the pack is covered
        var now = 1_000L
        mom.sync(disk, now = { now }).run()
        assertEquals(listOf(CloudSync.COVERED_UNREAD), mom.cursor.cursor.seen.values.toList())
        mom.ring.put(5, Primitives.randomBytes(32)) // a key nothing pins: the covered pack's generation matters now
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        repeat(4) {
            mom.sync(broken, now = { now }).run()
            now += minute
        }
        assertEquals(1, broken.attempts, "tried once, then it waits its 5 minutes")
        assertEquals(1, mom.cursor.cursor.failing.values.single().count)
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
        var now = 1_000L
        assertEquals(1, mom.sync(broken, now = { now }).run().synced().skipped)
        assertTrue(newest in mom.store.liveEntries(), "the later pack is applied")
        assertEquals(0L, mom.store.versionVector()[kid.id])
        now += 6 * minute
        mom.sync(disk, now = { now }).run()
        assertEquals(3L, mom.store.versionVector()[kid.id])
    }

    /**
     * Review Minor 1: a pack read ahead of a skipped one of its author, with an entry refused, is judged again once the
     * earlier pack is in; the gap completion never makes that refusal final in between.
     */
    @Test
    fun aPackReadAheadWithARefusedEntryIsReadAgainAfterTheGap() = runTest {
        val (mom, dad) = family(DeviceRole.PARENT)
        dad.publish(dad.author.summary("2026-10-16", 1))
        dad.sync(disk).run() // pack 1-1
        dad.publish(dad.author.categoryOverride("app.example", 1, rank = 1L shl 30)) // refused: rank over the limit
        dad.sync(disk).run() // pack 2-2
        val broken = BrokenPacks(disk, mutableSetOf(packOf(dad, 1, 1)))
        var now = 1_000L
        mom.sync(broken, now = { now }).run()
        assertTrue(mom.cursor.cursor.seen.keys.none { it.startsWith(dad.id.hex) }, "read ahead: not seen yet")
        now += 6 * minute
        broken.broken.clear()
        mom.sync(broken, now = { now }).run()
        assertEquals(2, broken.tried.count { it.endsWith(packOf(dad, 2, 2)) }, "read again once the gap is in")
        assertEquals(2, mom.cursor.cursor.seen.keys.count { it.startsWith(dad.id.hex) })
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

    /** Review Minor 2: a RuntimeException from the HTTP layer on one download is that file's trouble, not the run's. */
    @Test
    fun aRuntimeFailureOfOneDownloadIsSkippedToo() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 60_000))
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1))) { IllegalStateException("bad link") }
        assertEquals(1, mom.sync(broken).run().synced().skipped)
        assertEquals(1, mom.cursor.cursor.failing.values.single().count)
    }

    /** Review Minor 5: a failing pack compacted away (gone from the listing) is forgotten. */
    @Test
    fun aFailingPackGoneFromTheListingIsForgotten() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        kid.publish(kid.author.summary("2026-10-17", 60_000))
        kid.sync(disk).run()
        val broken = BrokenPacks(disk, mutableSetOf(packOf(kid, 1, 1)))
        mom.sync(broken).run()
        assertEquals(1, mom.cursor.cursor.failing.size)
        disk.deleteTree(CloudPaths.deviceDir(kid.store.groupId, kid.id))
        mom.sync(broken).run()
        assertTrue(mom.cursor.cursor.failing.isEmpty())
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
        var now = 1_000L
        assertEquals(1, mom.sync(damaged, now = { now }).run().synced().skipped)
        assertTrue(mom.cursor.cursor.seen.isEmpty(), "not rejected for good")
        now += 6 * minute
        mom.sync(disk, now = { now }).run()
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
        val result = again.sync(broken).run().synced()
        assertEquals(Triple(1, 0, 1), Triple(result.uploadedPacks, result.received, result.skipped))
        mom.sync(disk).run()
        assertTrue(later in mom.store.liveEntries(), "seq 2 reached the Disk")
    }
}
