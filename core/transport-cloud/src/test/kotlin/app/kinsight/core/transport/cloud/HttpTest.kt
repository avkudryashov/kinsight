package app.kinsight.core.transport.cloud

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpTest {
    private val y = FakeYandex()

    @AfterTest
    fun close() = y.close()

    @Test
    fun aRequestCarryingTheTokenDoesNotFollowRedirects() = runTest {
        y.on({ it.path == "/api" }) { _, ex -> y.respond(ex, 302, headers = mapOf("Location" to "${y.base}/elsewhere")) }
        y.on({ it.path == "/elsewhere" }) { _, ex -> y.respond(ex, 200, "{}") }
        val r = Http().request("GET", "${y.base}/api", mapOf("Authorization" to "OAuth TOKEN"))
        assertEquals(302, r.code)
        assertTrue(y.requests.none { it.path == "/elsewhere" }, "the token must not follow a redirect")
    }

    @Test
    fun aRequestWithoutTheTokenFollowsRedirects() = runTest {
        y.on({ it.path == "/link" }) { _, ex -> y.respond(ex, 302, headers = mapOf("Location" to "${y.base}/file")) }
        y.on({ it.path == "/file" }) { _, ex -> y.respondBytes(ex, 200, byteArrayOf(1)) }
        assertEquals(200, Http().request("GET", "${y.base}/link").code)
    }
}
