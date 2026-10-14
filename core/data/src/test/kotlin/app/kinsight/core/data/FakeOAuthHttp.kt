package app.kinsight.core.data

import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.Http
import app.kinsight.core.transport.cloud.HttpResponse
import app.kinsight.core.transport.cloud.YandexOAuth
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/**
 * The Yandex token endpoint: a code starting with "bad" is refused (invalid_grant), one starting with "busy" gets a 503
 * without an error field, one starting with "down" a 503 with the OAuth error temporarily_unavailable, anything else gets [accessToken] for a year. [unreachable]: the next request fails with no answer.
 * [gate]: a request waits for it after [entered] completes (a slow endpoint).
 */
class FakeOAuthHttp(var accessToken: String = "AT1") : Http() {
    val forms = mutableListOf<Map<String, String>>()
    var unreachable = false
    var gate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        if (unreachable) {
            unreachable = false
            throw IOException("no route to host")
        }
        entered.complete(Unit)
        gate?.await()
        val form = body!!.decodeToString().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        forms += form
        return if (form["code"]?.startsWith("bad") == true) {
            HttpResponse(400, """{"error":"invalid_grant"}""".encodeToByteArray(), emptyMap())
        } else if (form["code"]?.startsWith("down") == true) {
            HttpResponse(503, """{"error":"temporarily_unavailable"}""".encodeToByteArray(), emptyMap())
        } else if (form["code"]?.startsWith("busy") == true) {
            HttpResponse(503, "{}".encodeToByteArray(), emptyMap())
        } else {
            HttpResponse(200, """{"access_token":"$accessToken","refresh_token":"RT","expires_in":31536000}""".encodeToByteArray(), emptyMap())
        }
    }
}

val TEST_CLOUD = CloudConfig("c".repeat(32), "s".repeat(32), CloudConfig.CODE_PAGE)

fun TestDevice.signIn(http: FakeOAuthHttp, disk: (suspend () -> String) -> DiskApi, onEnabled: () -> Unit = {}) = CloudSignIn(
    db, groups, keyProvider, appendOwn, cloudSecrets,
    config = { TEST_CLOUD },
    disk = disk,
    oauth = { c -> YandexOAuth(c.clientId, c.clientSecret, http = http, clock = { now }) },
    onEnabled = onEnabled,
    clock = { now },
)

fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
    val (k, v) = it.split('=', limit = 2)
    k to URLDecoder.decode(v, "UTF-8")
}
