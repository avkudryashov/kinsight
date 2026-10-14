package app.kinsight.core.data

import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.Http
import app.kinsight.core.transport.cloud.HttpResponse
import app.kinsight.core.transport.cloud.YandexOAuth
import java.net.URI
import java.net.URLDecoder

/** The Yandex token endpoint: a code starting with "bad" is refused (invalid_grant), anything else gets [accessToken] for a year. */
class FakeOAuthHttp(var accessToken: String = "AT1") : Http() {
    val forms = mutableListOf<Map<String, String>>()

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val form = body!!.decodeToString().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        forms += form
        return if (form["code"]?.startsWith("bad") == true) {
            HttpResponse(400, """{"error":"invalid_grant"}""".encodeToByteArray(), emptyMap())
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
