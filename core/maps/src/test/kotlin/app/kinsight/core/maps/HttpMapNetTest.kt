package app.kinsight.core.maps

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline map spec §2: GitHub answers a release download with a redirect to its file host; errors are IOExceptions. */
class HttpMapNetTest {
    private val catalog = """{"version":"2026-10","blocks":[]}"""
    private val block = ByteArray(70_000) { it.toByte() }
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
        createContext("/catalog.json") { respond(it, 200, catalog.toByteArray()) }
        createContext("/moved") {
            it.responseHeaders.add("Location", "/block")
            respond(it, 302, ByteArray(0))
        }
        createContext("/block") { respond(it, 200, block) }
        createContext("/toforeign") {
            it.responseHeaders.add("Location", "http://localhost:${address.port}/block")
            respond(it, 302, ByteArray(0))
        }
        createContext("/loop") {
            it.responseHeaders.add("Location", "/loop")
            respond(it, 302, ByteArray(0))
        }
        createContext("/big") { respond(it, 200, ByteArray(HttpMapNet.MAX_CATALOG_BYTES + 1)) }
        createContext("/ua") { respond(it, 200, (it.requestHeaders.getFirst("User-Agent") ?: "").toByteArray()) }
        createContext("/missing") { respond(it, 404, ByteArray(0)) }
        createContext("/planet") { respond(it, 200, tileJson.toByteArray()) }
        createContext("/ae") { respond(it, 200, (it.requestHeaders.getFirst("Accept-Encoding") ?: "").toByteArray()) }
        createContext("/tile-gz") {
            it.responseHeaders.add("Content-Encoding", "gzip")
            respond(it, 200, gz)
        }
        createContext("/tile-plain") { respond(it, 200, plain) }
        createContext("/tile-br") {
            it.responseHeaders.add("Content-Encoding", "br")
            respond(it, 200, plain)
        }
        createContext("/empty") {
            it.sendResponseHeaders(204, -1)
            it.close()
        }
        createContext("/bigtile") { respond(it, 200, ByteArray(HttpMapNet.MAX_TILE_BYTES + 1)) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    /** Plain http to the loopback server, but only by its numeric address. */
    private val local = UrlPolicy { it.scheme == "http" && it.host == "127.0.0.1" }
    private val localAndName = UrlPolicy { it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost") }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theCatalogIsReadAsText() = assertEquals(catalog, HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = local).catalog())

    @Test
    fun aBlockFollowsTheRedirect() = assertEquals(block.toList(), HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = local).open("$base/moved").use { it.readBytes() }.toList())

    @Test
    fun anErrorStatusIsAnIOException() {
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/missing", policy = local).catalog() }
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = local).open("$base/missing") }
    }

    @Test
    fun productionRefusesPlainHttpAndForeignSchemes() {
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/catalog.json").catalog() }
        assertFailsWith<IOException> { HttpMapNet(ua).open("http://github.com/x") }
        assertFailsWith<IOException> { HttpMapNet(ua).open("file:///etc/passwd") }
        assertFailsWith<IOException> { HttpMapNet(ua).open("https://evil.example/x") }
        assertFailsWith<IOException> { HttpMapNet(ua).open("https://github.com@evil.example/x") }
    }

    @Test
    fun aRedirectToAnotherSchemeOrHostIsRefused() {
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = local).open("$base/toforeign") }
        val onlyHttpsAfterTheFirst = UrlPolicy { it.host == "127.0.0.1" && (it.scheme == "https" || it.path == "/toforeign") }
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = onlyHttpsAfterTheFirst).open("$base/toforeign") }
    }

    @Test
    fun aRedirectToAnAllowedHostIsFollowed() =
        assertEquals(block.toList(), HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = localAndName).open("$base/toforeign").use { it.readBytes() }.toList())

    @Test
    fun aRedirectLoopStopsAtTheHopLimit() {
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/catalog.json", policy = local).open("$base/loop") }
    }

    @Test
    fun aCatalogOverTheCapIsRefused() {
        assertFailsWith<IOException> { HttpMapNet(ua, catalogUrl = "$base/big", policy = local).catalog() }
    }

    private fun net(path: String = "/planet", policy: UrlPolicy = local) = HttpMapNet(ua, tileJsonUrl = "$base$path", policy = policy)

    @Test
    fun theTileJsonIsReadAsText() = assertEquals(tileJson, net().tileJson())

    @Test
    fun aGzipTileStaysGzip() {
        val t = assertNotNull(net().tile("$base/tile-gz"))
        assertTrue(t.gzip)
        assertContentEquals(gz, t.bytes)
    }

    /** Review Focus 5: a tile sent without gzip says so; MapSync gzips it before it goes into the archive. */
    @Test
    fun aPlainTileIsMarkedPlain() {
        val t = assertNotNull(net().tile("$base/tile-plain"))
        assertFalse(t.gzip)
        assertContentEquals(plain, t.bytes)
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
    fun aMissingTileMeansTheBuildIsGone() {
        assertFailsWith<SourceGone> { net().tile("$base/missing") }
    }

    @Test
    fun aTileOverTheCapIsRefused() {
        assertFailsWith<IOException> { net().tile("$base/bigtile") }
    }

    @Test
    fun productionPolicyKnowsOpenFreeMapAndForNowGitHub() {
        assertEquals(true, UrlPolicy.Production.allows(URI("https://tiles.openfreemap.org/planet")))
        assertEquals(false, UrlPolicy.Production.allows(URI("http://tiles.openfreemap.org/planet")))
        assertEquals(false, UrlPolicy.Production.allows(URI("https://user@tiles.openfreemap.org/planet")))
    }

    /** Spec §7, OpenFreeMap terms: a User-Agent of its own, naming the app and its version. */
    @Test
    fun theUserAgentNamesTheApp() = assertEquals("Kinsight/0.8.3 (+https://github.com/avkudryashov/kinsight)", net("/ua").tileJson())
}
