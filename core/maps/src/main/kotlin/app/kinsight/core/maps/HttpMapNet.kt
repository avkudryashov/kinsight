package app.kinsight.core.maps

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * The map's only network path (offline map spec §2): plain GETs of the catalog and of whole block files, redirects
 * followed by hand (at most [MAX_HOPS]) and every hop checked by the [UrlPolicy]. No cookies, no cache, a neutral
 * User-Agent.
 */
class HttpMapNet(
    private val catalogUrl: String = MapLinks.CATALOG_URL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val policy: UrlPolicy = UrlPolicy.Production,
) : MapNet {
    override fun catalog(): String {
        val c = connect(catalogUrl)
        try {
            return c.inputStream.use(::readCapped).decodeToString()
        } finally {
            c.disconnect()
        }
    }

    override fun open(url: String): InputStream {
        val c = connect(url)
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

    private fun connect(url: String): HttpURLConnection {
        var uri = parse(url)
        repeat(MAX_HOPS + 1) {
            if (!policy.allows(uri)) throw IOException("address refused")
            val c = uri.toURL().openConnection() as? HttpURLConnection ?: throw IOException("not an HTTP address")
            try {
                c.connectTimeout = connectTimeoutMs
                c.readTimeout = readTimeoutMs
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("User-Agent", USER_AGENT)
                when (val code = c.responseCode) {
                    HttpURLConnection.HTTP_OK -> return c
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                        uri = parse(uri.resolve(location).toString())
                    }
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

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_CATALOG_BYTES) throw IOException("catalog over $MAX_CATALOG_BYTES bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_CATALOG_BYTES = 1 shl 20
        const val MAX_HOPS = 5
        private const val USER_AGENT = "Kinsight"
    }
}

/** Which addresses the map may fetch, asked for the first URL and for every redirect hop (offline map spec §2). */
fun interface UrlPolicy {
    fun allows(uri: URI): Boolean

    companion object {
        private val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        /** https only, GitHub and its release file hosts only. */
        val Production = UrlPolicy { it.scheme == "https" && it.host in HOSTS && it.userInfo == null }
    }
}
