package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 S256 (spec §3: sign-in works without the client secret). */
object Pkce {
    fun verifier(random: SecureRandom = SecureRandom()): String = b64(Primitives.randomBytes(48, random))
    fun challenge(verifier: String): String = b64(Primitives.sha256(verifier.encodeToByteArray()))
    fun state(random: SecureRandom = SecureRandom()): String = b64(Primitives.randomBytes(16, random))
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
    override fun toString() = "TokenSet(expiresAtMs=$expiresAtMs, refresh=${refreshToken != null})"
}

/**
 * `invalid_grant`: the code or refresh token is dead, sign in again; `invalid_client`: wrong id or secret. [status]: the
 * HTTP status (0 when the answer was 200 but unusable). [retryable]: the endpoint is in trouble, not the request (spec §11:
 * 5xx and 429, or `server_error` / `temporarily_unavailable` whatever the status); the same request may be sent again.
 */
class OAuthException(val error: String, val description: String?, val status: Int = 0) : IOException("$error: $description") {
    val retryable: Boolean get() = status == 429 || status in 500..599 || error in RETRYABLE_ERRORS

    private companion object {
        val RETRYABLE_ERRORS = setOf("server_error", "temporarily_unavailable")
    }
}

/**
 * Yandex OAuth (spec §3, §6). [clientSecret] ships in the APK (spec §2) and is needed only to refresh and revoke;
 * sign-in uses PKCE without it.
 */
class YandexOAuth(
    private val clientId: String,
    private val clientSecret: String?,
    private val base: String = OAUTH_BASE,
    private val http: Http = Http(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [deviceId] (6..50 chars) makes the token revocable on its own (spec §6); [state] must come back unchanged. */
    fun authorizeUrl(challenge: String, redirectUri: String, deviceId: String, deviceName: String, state: String): String =
        "$base/authorize?" + form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "device_id" to deviceId,
            "device_name" to deviceName.take(100),
            "state" to state,
        )

    suspend fun exchange(code: String, verifier: String): TokenSet =
        token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "code_verifier" to verifier)

    suspend fun refresh(refreshToken: String): TokenSet = token(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientId,
        "client_secret" to secret(),
    )

    /** True when Yandex confirmed the revocation. */
    suspend fun revoke(accessToken: String): Boolean =
        post("/revoke_token", "access_token" to accessToken, "client_id" to clientId, "client_secret" to secret()).code == 200

    private fun secret() = clientSecret ?: throw IllegalStateException("refresh and revoke need the client secret")

    private suspend fun token(vararg params: Pair<String, String>): TokenSet {
        val r = post("/token", *params)
        // Safe casts throughout: a body that is not the expected shape is an IOException, never an IllegalArgumentException.
        val o = try {
            Json.parseToJsonElement(r.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw IOException("token endpoint answered ${r.code} without a JSON object")
        if (r.code != 200) throw OAuthException(o.str("error") ?: "http_${r.code}", o.str("error_description"), r.code)
        val access = o.str("access_token") ?: throw OAuthException("no_access_token", null)
        val expiresIn = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 0L
        return TokenSet(access, o.str("refresh_token"), clock() + expiresIn * 1000)
    }

    private suspend fun post(path: String, vararg params: Pair<String, String>): HttpResponse =
        http.request("POST", base + path, mapOf("Content-Type" to "application/x-www-form-urlencoded"), form(*params).encodeToByteArray())

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val OAUTH_BASE = "https://oauth.yandex.ru"

        // The "UTF-8" String overload: the Charset one needs API 33, minSdk is 31.
        private fun form(vararg p: Pair<String, String>) =
            p.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
    }
}
