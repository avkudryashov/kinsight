package app.kinsight.core.transport.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder

class Recorded(val method: String, val path: String, val query: Map<String, String>, val authorization: String?, val body: ByteArray)

class FakeYandex : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = mutableListOf<Recorded>()
    private val routes = mutableListOf<Pair<(Recorded) -> Boolean, (Recorded, HttpExchange) -> Unit>>()

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            val r = Recorded(ex.requestMethod, ex.requestURI.path, q, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes())
            synchronized(requests) { requests += r }
            val route = routes.firstOrNull { it.first(r) }
            if (route == null) respond(ex, 404, """{"error":"DiskNotFoundError"}""") else route.second(r, ex)
        }
        server.start()
    }

    fun on(match: (Recorded) -> Boolean, answer: (Recorded, HttpExchange) -> Unit) {
        routes += match to answer
    }

    fun respond(ex: HttpExchange, code: Int, json: String = "", headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = json.encodeToByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    fun respondBytes(ex: HttpExchange, code: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
