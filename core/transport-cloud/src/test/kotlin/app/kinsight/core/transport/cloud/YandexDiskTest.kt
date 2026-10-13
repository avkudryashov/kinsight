package app.kinsight.core.transport.cloud

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
}
