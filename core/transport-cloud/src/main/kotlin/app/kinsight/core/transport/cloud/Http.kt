package app.kinsight.core.transport.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class HttpResponse(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    fun text(): String = body.decodeToString()
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/** Plain HttpURLConnection (cloud plan R6). Redirects are followed; no cookies, no cache. */
open class Http(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxBodyBytes: Int = 32 shl 20,
) {
    open suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = true
            c.useCaches = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
            HttpResponse(code, bytes, c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() })
        } finally {
            c.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val bytes = input.readNBytes(maxBodyBytes + 1)
        if (bytes.size > maxBodyBytes) throw IOException("response over $maxBodyBytes bytes")
        return bytes
    }
}
