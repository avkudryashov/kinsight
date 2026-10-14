package app.kinsight.core.transport.cloud

import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YandexDiskTest {
    private val y = FakeYandex()
    private val disk = YandexDisk({ "TOKEN" }, base = "${y.base}/v1/disk")

    @AfterTest
    fun close() = y.close()

    @Test
    fun uploadGetsALinkWithTheTokenAndPutsWithoutIt() = runTest {
        y.on({ it.path == "/v1/disk/resources/upload" }) { _, ex -> y.respond(ex, 200, """{"href":"${y.base}/up/1","method":"PUT"}""") }
        y.on({ it.path == "/up/1" }) { _, ex -> y.respond(ex, 201) }
        disk.upload("app:/Kinsight/g/x.kb", byteArrayOf(1, 2, 3))
        val link = y.requests.first { it.path == "/v1/disk/resources/upload" }
        assertEquals("OAuth TOKEN", link.authorization)
        assertEquals("app:/Kinsight/g/x.kb", link.query["path"])
        assertEquals("true", link.query["overwrite"])
        val put = y.requests.first { it.path == "/up/1" }
        assertEquals("PUT", put.method)
        assertNull(put.authorization, "the token must never go to the upload host")
        assertContentEquals(byteArrayOf(1, 2, 3), put.body)
    }

    @Test
    fun downloadFollowsTheLinkWithoutTheToken() = runTest {
        y.on({ it.path == "/v1/disk/resources/download" }) { _, ex -> y.respond(ex, 200, """{"href":"${y.base}/dl/1","method":"GET"}""") }
        y.on({ it.path == "/dl/1" }) { _, ex -> y.respond(ex, 302, headers = mapOf("Location" to "${y.base}/dl/2")) }
        y.on({ it.path == "/dl/2" }) { _, ex -> y.respondBytes(ex, 200, byteArrayOf(9, 8)) }
        assertContentEquals(byteArrayOf(9, 8), disk.download("app:/Kinsight/g/x.kb"))
        assertNull(y.requests.first { it.path == "/dl/1" }.authorization)
        assertNull(y.requests.first { it.path == "/dl/2" }.authorization)
    }

    @Test
    fun listPagesThroughEmbeddedItems() = runTest {
        y.on({ it.path == "/v1/disk/resources" && it.method == "GET" }) { r, ex ->
            val offset = r.query["offset"]!!.toInt()
            val items = if (offset == 0) {
                """[{"name":"a","type":"dir"},{"name":"b.kb","type":"file","size":3,"sha256":"ff"}]"""
            } else {
                """[{"name":"c.kb","type":"file","size":1}]"""
            }
            y.respond(ex, 200, """{"_embedded":{"items":$items,"total":3,"limit":2,"offset":$offset}}""")
        }
        val items = YandexDisk({ "T" }, base = "${y.base}/v1/disk", pageSize = 2).list("app:/Kinsight")
        assertEquals(listOf("a", "b.kb", "c.kb"), items.map { it.name })
        assertEquals(true, items[0].isDir)
        assertEquals("ff", items[1].sha256)
        assertNull(items[2].sha256)
    }

    @Test
    fun statusCodesMapToDiskExceptions() = runTest {
        y.on({ it.query["path"] == "app:/401" }) { _, ex -> y.respond(ex, 401, """{"error":"UnauthorizedError"}""") }
        y.on({ it.query["path"] == "app:/507" }) { _, ex -> y.respond(ex, 507, """{"error":"DiskInsufficientStorageError"}""") }
        y.on({ it.query["path"] == "app:/429" }) { _, ex -> y.respond(ex, 429, "{}", mapOf("Retry-After" to "7")) }
        y.on({ it.query["path"] == "app:/503" }) { _, ex -> y.respond(ex, 503, "{}") }
        assertFailsWith<DiskException.Unauthorized> { disk.list("app:/401") }
        assertFailsWith<DiskException.StorageFull> { disk.upload("app:/507", byteArrayOf()) }
        assertEquals(7_000L, assertFailsWith<DiskException.RateLimited> { disk.list("app:/429") }.retryAfterMs)
        assertFailsWith<DiskException.Server> { disk.list("app:/503") }
        assertFailsWith<DiskException.NotFound> { disk.list("app:/missing") }
    }

    @Test
    fun mkdirOfAnExistingFolderAndDeleteOfAMissingPathAreQuiet() = runTest {
        y.on({ it.method == "PUT" && it.query["path"] == "app:/Kinsight" }) { _, ex -> y.respond(ex, 409, """{"error":"DiskPathPointsToExistentDirectoryError"}""") }
        y.on({ it.method == "PUT" && it.query["path"] == "app:/a/b" }) { _, ex -> y.respond(ex, 409, """{"error":"DiskPathDoesntExistsError"}""") }
        y.on({ it.method == "DELETE" }) { r, ex -> y.respond(ex, 404, """{"error":"DiskNotFoundError"}""").also { assertEquals("true", r.query["permanently"]) } }
        disk.mkdir("app:/Kinsight")
        assertFailsWith<DiskException.Conflict> { disk.mkdir("app:/a/b") }
        disk.delete("app:/gone")
    }

    @Test
    fun malformedListingsSurfaceAsIoException() = runTest {
        y.on({ it.query["path"] == "app:/bad1" }) { _, ex -> y.respond(ex, 200, """{"_embedded":[]}""") }
        y.on({ it.query["path"] == "app:/bad2" }) { _, ex -> y.respond(ex, 200, """{"_embedded":{"items":{}}}""") }
        y.on({ it.query["path"] == "app:/bad3" }) { _, ex -> y.respond(ex, 200, """{"_embedded":{"items":[5,{"name":"ok","type":"file"}],"total":2}}""") }
        assertFailsWith<java.io.IOException> { disk.list("app:/bad1") }
        assertFailsWith<java.io.IOException> { disk.list("app:/bad2") }
        assertEquals(listOf("ok"), disk.list("app:/bad3").map { it.name }) // a non-object item is skipped
    }

    @Test
    fun missingTotalKeepsPagingWhilePagesAreFull() = runTest {
        y.on({ it.path == "/v1/disk/resources" }) { r, ex ->
            val items = if (r.query["offset"] == "0") """[{"name":"a","type":"file"},{"name":"b","type":"file"}]""" else """[{"name":"c","type":"file"}]"""
            y.respond(ex, 200, """{"_embedded":{"items":$items}}""")
        }
        val items = YandexDisk({ "T" }, base = "${y.base}/v1/disk", pageSize = 2).list("app:/x")
        assertEquals(listOf("a", "b", "c"), items.map { it.name })
    }

    @Test
    fun notFoundCarriesTheDiskPath() = runTest {
        assertEquals("app:/missing", assertFailsWith<DiskException.NotFound> { disk.list("app:/missing") }.path)
    }

    @Test
    fun listingKeepsMd5AndModifiedForAVersionWithoutSha256() = runTest {
        y.on({ it.path == "/v1/disk/resources" }) { _, ex ->
            y.respond(ex, 200, """{"_embedded":{"items":[{"name":"x.kb","type":"file","size":3,"md5":"aa","modified":"2026-10-14T10:00:00+00:00"}],"total":1}}""")
        }
        val item = disk.list("app:/Kinsight/g").single()
        assertNull(item.sha256)
        assertEquals("aa", item.md5)
        assertEquals("2026-10-14T10:00:00+00:00", item.modified)
        val fields = y.requests.single().query["fields"]!!
        assertTrue("_embedded.items.md5" in fields && "_embedded.items.modified" in fields, fields)
    }
}
