package app.kinsight.core.maps

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

/**
 * The map's only network path (offline map spec §7): plain GETs of OpenFreeMap's TileJSON and tiles, redirects followed
 * by hand (at most [MAX_HOPS]) and every hop checked by the [UrlPolicy]. No cookies, no cache, the app's own
 * User-Agent. Tiles ask for gzip themselves, so no layer below unpacks them and the Content-Encoding stays visible.
 * [catalogUrl], [catalog] and [open] serve the GitHub catalog until Task 3 removes it.
 */
class HttpMapNet(
    private val userAgent: String,
    private val tileJsonUrl: String = MapLinks.TILEJSON_URL,
    private val catalogUrl: String = MapLinks.CATALOG_URL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val policy: UrlPolicy = UrlPolicy.Production,
) : MapNet {
    override fun catalog(): String = body(catalogUrl, IDENTITY, MAX_CATALOG_BYTES)?.first?.decodeToString() ?: throw IOException("HTTP 204")

    override fun open(url: String): InputStream {
        val c = connect(url, IDENTITY)
        val input = try {
            c.inputStream
        } catch (e: Throwable) {
            c.disconnect()
            throw e
        }
        return object : FilterInputStream(input) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    c.disconnect()
                }
            }
        }
    }

    override fun tileJson(): String = body(tileJsonUrl, IDENTITY, MAX_TILEJSON_BYTES)?.first?.decodeToString() ?: throw IOException("HTTP 204")

    override fun tile(url: String): Tile? {
        val (bytes, encoding) = body(url, GZIP, MAX_TILE_BYTES) ?: return null
        return when (encoding?.lowercase(Locale.ROOT)) {
            null, IDENTITY -> Tile(bytes, gzip = false)
            GZIP -> Tile(bytes, gzip = true)
            else -> throw IOException("unexpected Content-Encoding")
        }
    }

    /** The body (at most [cap] bytes) and its Content-Encoding; null for 204. */
    private fun body(url: String, acceptEncoding: String, cap: Int): Pair<ByteArray, String?>? {
        val c = connect(url, acceptEncoding)
        try {
            if (c.responseCode == HttpURLConnection.HTTP_NO_CONTENT) return null
            val bytes = c.inputStream.use { readCapped(it, cap) }
            return bytes to c.getHeaderField("Content-Encoding")
        } finally {
            c.disconnect()
        }
    }

    private fun connect(url: String, acceptEncoding: String): HttpURLConnection {
        var uri = parse(url)
        repeat(MAX_HOPS + 1) {
            if (!policy.allows(uri)) throw IOException("address refused")
            val c = uri.toURL().openConnection() as? HttpURLConnection ?: throw IOException("not an HTTP address")
            try {
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("Accept-Encoding", acceptEncoding)
                c.setRequestProperty("User-Agent", userAgent)
                when (val code = c.responseCode) {
                    HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NO_CONTENT -> return c
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                        uri = parse(uri.resolve(location).toString())
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> throw SourceGone()
                    else -> throw IOException("HTTP $code")
                }
            } catch (e: Throwable) {
                c.disconnect()
                throw e
            }
            c.disconnect()
        }
        throw IOException("too many redirects")
    }

    private fun parse(url: String): URI = try {
        URI(url)
    } catch (e: java.net.URISyntaxException) {
        throw IOException("bad address")
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) throw IOException("body over $cap bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_CATALOG_BYTES = 1 shl 20
        const val MAX_TILEJSON_BYTES = 1 shl 20

        /** OpenFreeMap's biggest z14 tiles in Valencia are under 600 KB gzip. */
        const val MAX_TILE_BYTES = 4 shl 20
        const val MAX_HOPS = 5
        private const val IDENTITY = "identity"
        private const val GZIP = "gzip"
    }
}

/** Which addresses the map may fetch, asked for the first URL and for every redirect hop (offline map spec §7). */
fun interface UrlPolicy {
    fun allows(uri: URI): Boolean

    companion object {
        private val HOSTS = setOf(MapLinks.TILE_HOST, "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        /** https only; OpenFreeMap's tile host (and, until Task 3, GitHub's release hosts). */
        val Production = UrlPolicy { it.scheme == "https" && it.host in HOSTS && it.userInfo == null }
    }
}
