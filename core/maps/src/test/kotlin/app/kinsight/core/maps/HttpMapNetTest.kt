package app.kinsight.core.maps

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Offline map spec §2: GitHub answers a release download with a redirect to its file host; errors are IOExceptions. */
class HttpMapNetTest {
    private val catalog = """{"version":"2026-10","blocks":[]}"""
    private val block = ByteArray(70_000) { it.toByte() }

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
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    /** Plain http to the loopback server, but only by its numeric address. */
    private val local = UrlPolicy { it.scheme == "http" && it.host == "127.0.0.1" }
    private val localAndName = UrlPolicy { it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost") }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theCatalogIsReadAsText() = assertEquals(catalog, HttpMapNet("$base/catalog.json", policy = local).catalog())

    @Test
    fun aBlockFollowsTheRedirect() = assertEquals(block.toList(), HttpMapNet("$base/catalog.json", policy = local).open("$base/moved").use { it.readBytes() }.toList())

    @Test
    fun anErrorStatusIsAnIOException() {
        assertFailsWith<IOException> { HttpMapNet("$base/missing", policy = local).catalog() }
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json", policy = local).open("$base/missing") }
    }

    @Test
    fun productionRefusesPlainHttpAndForeignSchemes() {
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json").catalog() }
        assertFailsWith<IOException> { HttpMapNet().open("http://github.com/x") }
        assertFailsWith<IOException> { HttpMapNet().open("file:///etc/passwd") }
        assertFailsWith<IOException> { HttpMapNet().open("https://evil.example/x") }
        assertFailsWith<IOException> { HttpMapNet().open("https://github.com@evil.example/x") }
    }

    @Test
    fun productionPolicyKnowsTheGitHubHosts() {
        listOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com").forEach {
            assertEquals(true, UrlPolicy.Production.allows(URI("https://$it/a")), it)
        }
        assertEquals(false, UrlPolicy.Production.allows(URI("http://github.com/a")))
    }

    @Test
    fun aRedirectToAnotherSchemeOrHostIsRefused() {
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json", policy = local).open("$base/toforeign") }
        val onlyHttpsAfterTheFirst = UrlPolicy { it.host == "127.0.0.1" && (it.scheme == "https" || it.path == "/toforeign") }
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json", policy = onlyHttpsAfterTheFirst).open("$base/toforeign") }
    }

    @Test
    fun aRedirectToAnAllowedHostIsFollowed() =
        assertEquals(block.toList(), HttpMapNet("$base/catalog.json", policy = localAndName).open("$base/toforeign").use { it.readBytes() }.toList())

    @Test
    fun aRedirectLoopStopsAtTheHopLimit() {
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json", policy = local).open("$base/loop") }
    }

    @Test
    fun aCatalogOverTheCapIsRefused() {
        assertFailsWith<IOException> { HttpMapNet("$base/big", policy = local).catalog() }
    }

    @Test
    fun theUserAgentIsNeutral() = assertEquals("Kinsight", HttpMapNet("$base/ua", policy = local).catalog())
}
