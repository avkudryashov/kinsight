package app.kinsight.core.transport.cloud

import kotlinx.coroutines.test.runTest
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YandexOAuthTest {
    private val y = FakeYandex()
    private val oauth = YandexOAuth("CID", "SECRET", base = y.base, clock = { 1_000L })

    @AfterTest
    fun close() = y.close()

    private fun form(body: ByteArray) = body.decodeToString().split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

    @Test
    fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun authorizeUrlCarriesTheChallengeAndDevice() {
        val url = URI(oauth.authorizeUrl("CH", "https://oauth.yandex.ru/verification_code", "ab".repeat(16), "Pixel 8a", "ST"))
        val q = url.rawQuery.split('&').associate { val (k, v) = it.split('='); k to URLDecoder.decode(v, Charsets.UTF_8) }
        assertEquals("/authorize", url.path)
        assertEquals("code", q["response_type"])
        assertEquals("CID", q["client_id"])
        assertEquals("CH", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("ab".repeat(16), q["device_id"])
        assertEquals("Pixel 8a", q["device_name"])
        assertEquals("ST", q["state"])
    }

    @Test
    fun exchangeSendsTheVerifierAndNoSecret() = runTest {
        y.on({ it.path == "/token" }) { _, ex -> y.respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","expires_in":31536000,"token_type":"bearer"}""") }
        val t = oauth.exchange("CODE", "VERIFIER")
        assertEquals(TokenSet("AT", "RT", 1_000L + 31_536_000_000L), t)
        val f = form(y.requests.single().body)
        assertEquals("authorization_code", f["grant_type"])
        assertEquals("VERIFIER", f["code_verifier"])
        assertFalse("client_secret" in f)
        assertFalse("AT" in t.toString())
    }

    @Test
    fun refreshAndRevokeUseTheSecret() = runTest {
        y.on({ it.path == "/token" }) { _, ex -> y.respond(ex, 200, """{"access_token":"AT2","refresh_token":"RT2","expires_in":10}""") }
        y.on({ it.path == "/revoke_token" }) { _, ex -> y.respond(ex, 200, """{"status":"ok"}""") }
        assertEquals("AT2", oauth.refresh("RT").accessToken)
        assertEquals("SECRET", form(y.requests[0].body)["client_secret"])
        assertEquals("refresh_token", form(y.requests[0].body)["grant_type"])
        assertTrue(oauth.revoke("AT2"))
        assertEquals("AT2", form(y.requests[1].body)["access_token"])
    }

    @Test
    fun errorsAreTyped() = runTest {
        y.on({ it.path == "/token" }) { _, ex -> y.respond(ex, 400, """{"error":"invalid_grant","error_description":"Code has expired"}""") }
        val e = assertFailsWith<OAuthException> { oauth.exchange("OLD", "V") }
        assertEquals("invalid_grant", e.error)
        assertFailsWith<IllegalStateException> { YandexOAuth("CID", null, base = y.base).refresh("RT") }
    }

    @Test
    fun structurallyUnexpectedJsonIsAnIoFailure() = runTest {
        y.on({ it.path == "/token" }) { _, ex -> y.respond(ex, 200, """[1,2]""") }
        assertFailsWith<java.io.IOException> { oauth.exchange("C", "V") }
    }

    @Test
    fun aTokenThatIsNotAStringIsAnOAuthFailure() = runTest {
        y.on({ it.path == "/token" }) { _, ex -> y.respond(ex, 200, """{"access_token":{"x":1},"expires_in":1}""") }
        assertFailsWith<OAuthException> { oauth.exchange("C", "V") }
    }
}
