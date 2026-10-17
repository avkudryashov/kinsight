package app.kinsight.core.maps

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline map spec §7: OpenFreeMap's TileJSON and tiles over https only; errors are IOExceptions. */
class HttpMapNetTest {
    private val ua = MapLinks.userAgent("0.8.3")
    private val tileJson = """{"tiles":["https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf"],"maxzoom":14}"""
    private val plain = ByteArray(5_000) { it.toByte() }
    private val gz = PmTiles.gzip(plain)

    private fun respond(ex: HttpExchange, code: Int, body: ByteArray) {
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/planet") { respond(it, 200, tileJson.toByteArray()) }
        createContext("/moved") {
            it.responseHeaders.add("Location", "/planet")
            respond(it, 302, ByteArray(0))
        }
        createContext("/toforeign") {
            it.responseHeaders.add("Location", "http://localhost:${address.port}/planet")
            respond(it, 302, ByteArray(0))
        }
        createContext("/loop") {
            it.responseHeaders.add("Location", "/loop")
            respond(it, 302, ByteArray(0))
        }
        createContext("/big") { respond(it, 200, ByteArray(HttpMapNet.MAX_TILEJSON_BYTES + 1)) }
        createContext("/bigtile") { respond(it, 200, ByteArray(HttpMapNet.MAX_TILE_BYTES + 1)) }
        createContext("/ua") { respond(it, 200, (it.requestHeaders.getFirst("User-Agent") ?: "").toByteArray()) }
        createContext("/ae") { respond(it, 200, (it.requestHeaders.getFirst("Accept-Encoding") ?: "").toByteArray()) }
        createContext("/tile-gz") {
            it.responseHeaders.add("Content-Encoding", "gzip")
            respond(it, 200, gz)
        }
        createContext("/tile-plain") { respond(it, 200, plain) }
        createContext("/tile-gz-bare") { respond(it, 200, gz) }
        createContext("/tile-br") {
            it.responseHeaders.add("Content-Encoding", "br")
            respond(it, 200, plain)
        }
        createContext("/empty") {
            it.sendResponseHeaders(204, -1)
            it.close()
        }
        createContext("/missing") { respond(it, 404, ByteArray(0)) }
        createContext("/broken") { respond(it, 500, ByteArray(0)) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    /** Plain http to the loopback server, but only by its numeric address. */
    private val local = UrlPolicy { it.scheme == "http" && it.host == "127.0.0.1" }
    private val localAndName = UrlPolicy { it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost") }

    private fun net(path: String = "/planet", policy: UrlPolicy = local) = HttpMapNet(ua, tileJsonUrl = "$base$path", policy = policy)

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theTileJsonIsReadAsText() = assertEquals(tileJson, net().tileJson())

    @Test
    fun aGzipTileStaysGzip() {
        val t = assertNotNull(net().tile("$base/tile-gz"))
        assertTrue(t.gzip)
        assertContentEquals(gz, t.bytes)
    }

    @Test
    fun aPlainTileIsMarkedPlain() {
        val t = assertNotNull(net().tile("$base/tile-plain"))
        assertFalse(t.gzip)
        assertContentEquals(plain, t.bytes)
    }

    /** No Content-Encoding but gzip bytes (1f 8b): gzip already, so it is not gzipped twice. */
    @Test
    fun gzipBytesWithoutTheHeaderAreGzip() {
        val t = assertNotNull(net().tile("$base/tile-gz-bare"))
        assertTrue(t.gzip)
        assertContentEquals(gz, t.bytes)
    }

    @Test
    fun anUnknownEncodingIsRefused() {
        assertFailsWith<IOException> { net().tile("$base/tile-br") }
    }

    @Test
    fun noContentIsNoTile() = assertNull(net().tile("$base/empty"))

    @Test
    fun tilesAskForGzipAndTheTileJsonForIdentity() {
        assertEquals("gzip", assertNotNull(net().tile("$base/ae")).bytes.decodeToString())
        assertEquals("identity", net("/ae").tileJson())
    }

    @Test
    fun aMissingTileMeansTheBuildIsGoneAndOtherErrorsAreIOExceptions() {
        assertFailsWith<SourceGone> { net().tile("$base/missing") }
        assertFailsWith<IOException> { net().tile("$base/broken") }
        assertFailsWith<IOException> { net("/broken").tileJson() }
    }

    @Test
    fun theRedirectIsFollowed() = assertEquals(tileJson, net("/moved").tileJson())

    @Test
    fun productionRefusesEverythingButOpenFreeMapOverHttps() {
        assertFailsWith<IOException> { net(policy = UrlPolicy.Production).tileJson() }
        assertFailsWith<IOException> { HttpMapNet(ua).tile("http://tiles.openfreemap.org/planet/20261011_080001_pt/10/510/389.pbf") }
        assertFailsWith<IOException> { HttpMapNet(ua).tile("file:///etc/passwd") }
        assertFailsWith<IOException> { HttpMapNet(ua).tile("https://evil.example/x") }
        assertFailsWith<IOException> { HttpMapNet(ua).tile("https://tiles.openfreemap.org@evil.example/x") }
    }

    @Test
    fun productionPolicyKnowsOnlyOpenFreeMap() {
        assertTrue(UrlPolicy.Production.allows(URI("https://tiles.openfreemap.org/planet")))
        listOf(
            "https://github.com/a", "https://objects.githubusercontent.com/a", "https://release-assets.githubusercontent.com/a",
            "http://tiles.openfreemap.org/planet", "https://user@tiles.openfreemap.org/planet", "https://tiles.openfreemap.org.evil.example/planet",
        ).forEach { assertFalse(UrlPolicy.Production.allows(URI(it)), it) }
    }

    @Test
    fun aRedirectToAnotherSchemeOrHostIsRefused() {
        assertFailsWith<IOException> { net("/toforeign").tileJson() }
        val onlyHttpsAfterTheFirst = UrlPolicy { it.host == "127.0.0.1" && (it.scheme == "https" || it.path == "/toforeign") }
        assertFailsWith<IOException> { net("/toforeign", onlyHttpsAfterTheFirst).tileJson() }
    }

    @Test
    fun aRedirectToAnAllowedHostIsFollowed() = assertEquals(tileJson, net("/toforeign", localAndName).tileJson())

    @Test
    fun aRedirectLoopStopsAtTheHopLimit() {
        assertFailsWith<IOException> { net("/loop").tileJson() }
    }

    @Test
    fun answersOverTheCapAreRefused() {
        assertFailsWith<IOException> { net("/big").tileJson() }
        assertFailsWith<IOException> { net().tile("$base/bigtile") }
    }

    @Test
    fun theUserAgentNamesTheApp() = assertEquals("Kinsight/0.8.3 (+https://github.com/avkudryashov/kinsight)", net("/ua").tileJson())
}
