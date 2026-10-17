package app.kinsight.core.maps

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
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
        createContext("/missing") { respond(it, 404, ByteArray(0)) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theCatalogIsReadAsText() = assertEquals(catalog, HttpMapNet("$base/catalog.json").catalog())

    @Test
    fun aBlockFollowsTheRedirect() = assertEquals(block.toList(), HttpMapNet("$base/catalog.json").open("$base/moved").use { it.readBytes() }.toList())

    @Test
    fun anErrorStatusIsAnIOException() {
        assertFailsWith<IOException> { HttpMapNet("$base/missing").catalog() }
        assertFailsWith<IOException> { HttpMapNet("$base/catalog.json").open("$base/missing") }
    }
}
