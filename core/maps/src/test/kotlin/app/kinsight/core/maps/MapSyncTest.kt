package app.kinsight.core.maps

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline map spec §3 and its «Жизненный цикл» tests, against a fake GitHub. */
class MapSyncTest {
    private class FakeNet : MapNet {
        var catalogText: String? = null
        var failCatalog = false
        val files = mutableMapOf<String, ByteArray>()
        var catalogCalls = 0
        val opened = mutableListOf<String>()

        override fun catalog(): String {
            catalogCalls++
            if (failCatalog) throw IOException("offline")
            return catalogText ?: throw IOException("HTTP 404")
        }

        override fun open(url: String): InputStream {
            opened += url
            return ByteArrayInputStream(files[url] ?: throw IOException("HTTP 404"))
        }
    }

    private val day = 24 * 3_600_000L
    private var now = 1_760_000_000_000L
    private val dir = createTempDirectory("maps").toFile()
    private var freeBytes = 10L shl 30
    private val store = BlockStore(dir) { freeBytes }
    private val net = FakeNet()
    private val sync = MapSync(store, net) { now }
    private val valencia = Cell(510, 389)
    private val v1 = "valencia v1".repeat(1_000).toByteArray()

    private fun point(t: Long = now) = MapPoint(395_000_000, -5_000_000, t)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun url(cell: Cell, version: String) = "${MapLinks.DOWNLOAD_PREFIX}maps-$version/${cell.name}.pmtiles"

    private fun serve(version: String, cell: Cell, bytes: ByteArray) {
        net.files[url(cell, version)] = bytes
        net.catalogText = """{"version":"$version","blocks":[{"x":${cell.x},"y":${cell.y},"bytes":${bytes.size},""" +
            """"sha256":"${sha(bytes)}","url":"${url(cell, version)}"}]}"""
    }

    private fun install() {
        serve("2026-10", valencia, v1)
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync())
    }

    @Test
    fun aPointInANewCellFetchesItsBlockOnce() {
        install()
        assertContentEquals(v1, store.file(valencia).readBytes())
        assertEquals(1, net.catalogCalls)
        assertEquals(listOf(url(valencia, "2026-10")), net.opened)
        now += 3_600_000L
        assertFalse(sync.check(listOf(point()), listOf(point())), "the block is here and the catalog is fresh")
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(1, net.catalogCalls)
        assertEquals(1, net.opened.size)
    }

    /** Spec §3: a child's phone has no children, so no points: it never goes online for the map. */
    @Test
    fun withoutPointsNothingGoesOnline() {
        serve("2026-10", valencia, v1)
        assertFalse(sync.check(emptyList(), emptyList()))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(0, net.catalogCalls)
        assertTrue(net.opened.isEmpty())
    }

    @Test
    fun aBlockWithoutPointsForAWeekIsDeleted() {
        install()
        now += 7 * day
        assertFalse(sync.check(emptyList(), emptyList()))
        assertFalse(store.file(valencia).exists())
        assertFalse(valencia in store.ledger().installed)
    }

    @Test
    fun sixDaysWithoutPointsKeepTheBlock() {
        install()
        now += 6 * day
        sync.check(emptyList(), emptyList())
        assertTrue(store.file(valencia).exists())
    }

    @Test
    fun theBlockWhereTheChildIsNowStays() {
        install()
        val there = point()
        now += 8 * day
        sync.check(emptyList(), listOf(there))
        assertTrue(store.file(valencia).exists())
    }

    @Test
    fun aNewerCatalogBringsTheBlockAgain() {
        install()
        val v2 = "valencia v2".repeat(1_200).toByteArray()
        serve("2026-11", valencia, v2)
        now += day
        assertTrue(sync.check(listOf(point()), listOf(point())), "the daily catalog look")
        assertEquals(SyncRun.DONE, sync.sync())
        assertContentEquals(v2, store.file(valencia).readBytes())
        assertEquals(sha(v2), store.ledger().installed[valencia])
    }

    /** Review Focus 3: a catalog that went back in time does not undo a newer one. */
    @Test
    fun anOlderCatalogNeverReplacesTheNewer() {
        install()
        serve("2026-09", valencia, "older".repeat(500).toByteArray())
        now += day
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.DONE, sync.sync())
        assertContentEquals(v1, store.file(valencia).readBytes())
        assertEquals("2026-10", store.catalog()?.version)
    }

    @Test
    fun theCatalogIsAskedAtMostOnceADay() {
        serve("2026-10", valencia, v1)
        fun sydney() = MapPoint(-338_688_000, 1_512_093_000, now)
        assertTrue(sync.check(listOf(sydney()), listOf(sydney())))
        assertEquals(SyncRun.DONE, sync.sync())
        assertEquals(1, net.catalogCalls)
        assertTrue(net.opened.isEmpty(), "the catalog has no block for this place")
        now += day - 1
        assertFalse(sync.check(listOf(sydney()), listOf(sydney())))
        now += 1
        assertTrue(sync.check(listOf(sydney()), listOf(sydney())))
    }

    @Test
    fun withLittleRoomTheBlockWaits() {
        serve("2026-10", valencia, v1)
        freeBytes = v1.size + (100L shl 20) - 1
        assertTrue(sync.check(listOf(point()), listOf(point())), "the catalog is due")
        assertEquals(SyncRun.DONE, sync.sync())
        assertTrue(net.opened.isEmpty())
        assertFalse(sync.check(listOf(point()), listOf(point())), "nothing to fetch until there is room")
        assertEquals(setOf(valencia), sync.streets(downloading = false).noRoom)
        freeBytes = 1L shl 30
        assertTrue(sync.check(listOf(point()), listOf(point())))
    }

    @Test
    fun aCorruptDownloadIsRetriedAndLeavesNoFile() {
        serve("2026-10", valencia, v1)
        net.files[url(valencia, "2026-10")] = "tampered".toByteArray()
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertFalse(store.file(valencia).exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun anUnreachableCatalogIsRetried() {
        net.failCatalog = true
        sync.check(listOf(point()), listOf(point()))
        assertEquals(SyncRun.RETRY, sync.sync())
        assertNull(store.ledger().catalogFetchedMs)
    }

    /** Review Focus 1: a child's clock ahead of this phone's never pins a block for longer than a week. */
    @Test
    fun aPointFromAheadOfTheClockStillAgesOut() {
        install()
        sync.check(listOf(point(now + 30 * day)), emptyList())
        now += 7 * day
        sync.check(emptyList(), emptyList())
        assertFalse(store.file(valencia).exists())
    }

    @Test
    fun theScreenSeesTheBlockAndTheCatalog() {
        install()
        val s = sync.streets(downloading = true)
        assertEquals(mapOf(valencia to store.file(valencia).absolutePath), s.blocks)
        assertEquals(setOf(valencia), s.catalog)
        assertTrue(s.noRoom.isEmpty())
        assertTrue(s.downloading)
    }

    /** A run that may not go on (the cloud went off, the phone left) asks for nothing more. */
    @Test
    fun aRunThatMayNotProceedAsksForNothing() {
        serve("2026-10", valencia, v1)
        assertTrue(sync.check(listOf(point()), listOf(point())))
        assertEquals(SyncRun.DONE, sync.sync(proceed = { false }))
        assertEquals(0, net.catalogCalls)
        assertTrue(net.opened.isEmpty())
        var asked = 0
        assertEquals(SyncRun.DONE, sync.sync(proceed = { asked++ == 0 }), "the catalog may go, the block may not")
        assertEquals(1, net.catalogCalls)
        assertTrue(net.opened.isEmpty())
        assertFalse(store.file(valencia).exists())
    }

    /** A leave in the middle of a block stops the read; the partial is deleted and no block appears. */
    @Test
    fun anAbortedDownloadLeavesNoBlock() {
        serve("2026-10", valencia, v1)
        assertTrue(sync.check(listOf(point()), listOf(point())))
        var reads = 0
        assertEquals(SyncRun.DONE, sync.sync(aborted = { reads++ > 1 }))
        assertEquals(1, net.opened.size)
        assertFalse(store.file(valencia).exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(store.ledger().installed.isEmpty())
    }
}
