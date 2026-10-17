package app.kinsight.core.transport.cloud

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Spec §13: the sync on a live Yandex Disk, opt in. `./gradlew :core:transport-cloud:test -Pkinsight.liveYandex=true` with
 * `kinsight.yandex.testToken` (an app-folder token of a test account) in local.properties. Skipped otherwise. Leaves
 * nothing behind on the Disk.
 */
class YandexLiveTest {
    private val token: String? = System.getenv("KINSIGHT_YANDEX_TEST_TOKEN")
    private val enabled = System.getenv("KINSIGHT_LIVE_YANDEX") == "true" && !token.isNullOrBlank()

    @Test
    fun twoPhonesSyncThroughALiveDisk() = runTest(timeout = 3.minutes) {
        assumeTrue(enabled, "live Yandex test is opt in")
        val disk = YandexDisk({ token!! })
        val (mom, kid) = family(DeviceRole.CHILD)
        keepingTheFailure({ disk.delete(CloudPaths.root(mom.store.groupId)) }) {
            val day = kid.author.summary("2026-10-13", 60_000)
            kid.publish(day)
            assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 0), kid.sync(disk).run())
            assertIs<CloudResult.Synced>(mom.sync(disk).run())
            assertTrue(day in mom.store.liveEntries())
        }
    }

    @Test
    fun aFailingCleanUpDoesNotHideTheFailure() = runTest {
        val failure = assertFailsWith<AssertionError> {
            keepingTheFailure({ throw IOException("delete failed") }) { throw AssertionError("the real failure") }
        }
        assertEquals("the real failure", failure.message)
        assertEquals("delete failed", failure.suppressed.single().message)
        val cleanUp = assertFailsWith<IOException> { keepingTheFailure({ throw IOException("delete failed") }) { } }
        assertEquals("delete failed", cleanUp.message)
    }

    /**
     * Spec §14 «Выдаёт ли refresh_token новый access-токен ближе к истечению срока»: refreshes the test account's grant and
     * prints, without any token, whether Yandex returned another access token. Needs kinsight.yandex.testRefreshToken,
     * kinsight.yandex.clientId and kinsight.yandex.clientSecret in local.properties. Yandex answers with a new refresh
     * token: the stored one may stop working, in which case the user takes a new one for the next run.
     */
    @Test
    fun aRefreshAnswersWithAnAccessToken() = runTest(timeout = 1.minutes) {
        val refresh = System.getenv("KINSIGHT_YANDEX_TEST_REFRESH")
        val clientId = System.getenv("KINSIGHT_YANDEX_CLIENT_ID")
        val secret = System.getenv("KINSIGHT_YANDEX_CLIENT_SECRET")
        assumeTrue(
            System.getenv("KINSIGHT_LIVE_YANDEX") == "true" && !refresh.isNullOrBlank() && !clientId.isNullOrBlank() && !secret.isNullOrBlank(),
            "live refresh test is opt in",
        )
        val t = YandexOAuth(clientId!!, secret!!).refresh(refresh!!)
        assertTrue(t.accessToken.isNotEmpty())
        val days = (t.expiresAtMs - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)
        println("live refresh: access token changed=${token != null && t.accessToken != token}, refresh token changed=${t.refreshToken != refresh}, days left=$days")
    }
}

/**
 * Runs [body], then [cleanUp]. A failure of [body] is the one reported: a clean-up that fails as well is attached to it as
 * suppressed instead of replacing it (a plain `finally` would). A clean-up failure after a passing body is reported.
 */
internal suspend fun <T> keepingTheFailure(cleanUp: suspend () -> Unit, body: suspend () -> T): T {
    val result = try {
        body()
    } catch (e: Throwable) {
        try {
            cleanUp()
        } catch (c: Throwable) {
            e.addSuppressed(c)
        }
        throw e
    }
    cleanUp()
    return result
}
