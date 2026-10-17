package app.kinsight.core.maps

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * The map's only network path (offline map spec §2): plain GETs of the catalog and of whole block files, redirects
 * followed to GitHub's file host. No cookies, no cache, no headers of ours beyond the request itself.
 */
class HttpMapNet(
    private val catalogUrl: String = MapLinks.CATALOG_URL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
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
        return object : FilterInputStream(c.inputStream) {
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
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.useCaches = false
        c.setRequestProperty("Accept-Encoding", "identity")
        val code = try {
            c.responseCode
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
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
    }
}
