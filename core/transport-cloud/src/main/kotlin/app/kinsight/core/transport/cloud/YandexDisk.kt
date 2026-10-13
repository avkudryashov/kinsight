package app.kinsight.core.transport.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * Yandex Disk REST API v1 on the app folder (spec §3: a cloud_api:disk.app_folder token reaches app:/ only). The token
 * goes to [base] only; upload and download links point at other hosts and get no Authorization header (cloud plan R6).
 */
class YandexDisk(
    private val accessToken: suspend () -> String,
    private val base: String = API_BASE,
    private val http: Http = Http(),
    private val pageSize: Int = 1000,
) : DiskApi {

    override suspend fun list(path: String): List<DiskItem> {
        val out = mutableListOf<DiskItem>()
        var offset = 0
        while (true) {
            val r = api("GET", "/resources?path=${enc(path)}&limit=$pageSize&offset=$offset&fields=$FIELDS", path)
            val root = json(r)
            val embedded = when (val e = root["_embedded"]) {
                null -> return out
                is JsonObject -> e
                else -> throw IOException("unexpected _embedded in listing")
            }
            val items = when (val i = embedded["items"]) {
                null -> JsonArray(emptyList())
                is JsonArray -> i
                else -> throw IOException("unexpected items in listing")
            }
            for (element in items) {
                val o = element as? JsonObject ?: continue // a malformed entry is skipped, not fatal
                out += DiskItem(
                    name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue,
                    isDir = (o["type"] as? JsonPrimitive)?.contentOrNull == "dir",
                    size = (o["size"] as? JsonPrimitive)?.longOrNull ?: 0,
                    sha256 = (o["sha256"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            offset += items.size
            val total = (embedded["total"] as? JsonPrimitive)?.longOrNull
            if (items.isEmpty() || (total != null && offset >= total) || (total == null && items.size < pageSize)) return out
        }
    }

    override suspend fun mkdir(path: String) {
        val r = raw("PUT", "/resources?path=${enc(path)}")
        if (r.code == 201) return
        if (r.code == 409 && errorOf(r) == "DiskPathPointsToExistentDirectoryError") return
        throw fail(r, path)
    }

    override suspend fun upload(path: String, bytes: ByteArray) {
        val link = json(api("GET", "/resources/upload?path=${enc(path)}&overwrite=true", path))
        val r = http.request(method(link, "PUT"), href(link), body = bytes)
        if (r.code !in 200..299) throw fail(r, path)
    }

    override suspend fun download(path: String): ByteArray {
        val link = json(api("GET", "/resources/download?path=${enc(path)}", path))
        val r = http.request(method(link, "GET"), href(link))
        if (r.code != 200) throw fail(r, path)
        return r.body
    }

    override suspend fun delete(path: String) {
        val r = raw("DELETE", "/resources?path=${enc(path)}&permanently=true")
        if (r.code == 404 || r.code in 200..299) return
        throw fail(r, path)
    }

    private suspend fun api(method: String, pathAndQuery: String, path: String): HttpResponse {
        val r = raw(method, pathAndQuery)
        if (r.code !in 200..299) throw fail(r, path)
        return r
    }

    private suspend fun raw(method: String, pathAndQuery: String): HttpResponse =
        http.request(method, base + pathAndQuery, mapOf("Authorization" to "OAuth ${accessToken()}", "Accept" to "application/json"))

    companion object {
        const val API_BASE = "https://cloud-api.yandex.net/v1/disk"
        private const val FIELDS = "_embedded.items.name,_embedded.items.type,_embedded.items.size,_embedded.items.sha256,_embedded.total"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun json(r: HttpResponse): JsonObject = try {
            Json.parseToJsonElement(r.text()).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("not JSON (${r.code})", e)
        }

        private fun href(link: JsonObject) = link["href"]?.jsonPrimitive?.contentOrNull ?: throw IOException("link without href")
        private fun method(link: JsonObject, default: String) = link["method"]?.jsonPrimitive?.contentOrNull ?: default

        private fun errorOf(r: HttpResponse): String? = runCatching { json(r)["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()

        fun fail(r: HttpResponse, what: String): IOException = when (r.code) {
            401 -> DiskException.Unauthorized()
            404 -> DiskException.NotFound(what)
            409 -> DiskException.Conflict(errorOf(r))
            429 -> DiskException.RateLimited(r.header("retry-after")?.toLongOrNull()?.times(1000))
            507 -> DiskException.StorageFull()
            in 500..599 -> DiskException.Server(r.code)
            else -> DiskException.Unexpected(r.code, errorOf(r) ?: "")
        }
    }
}
