import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseGatesTest {
    private val fakeId = "0123456789abcdef0123456789abcdef"
    private val fakeSecret = "fedcba9876543210fedcba9876543210"

    @Test
    fun aYandexClientFromLocalPropertiesPasses() {
        val dir = Files.createTempDirectory("gates").toFile()
        val local = File(dir, "local.properties").apply {
            writeText("${ReleaseGates.YANDEX_CLIENT_ID_PROPERTY}=$fakeId\n${ReleaseGates.YANDEX_CLIENT_SECRET_PROPERTY}=$fakeSecret\n")
        }
        val c = ReleaseGates.yandexClient({ null }, local)
        assertNull(ReleaseGates.yandexClientProblem(c))
        assertEquals(fakeId, ReleaseGates.yandexClientId(c))
        assertEquals(fakeSecret, ReleaseGates.yandexClientSecret(c))
        assertEquals(ReleaseGates.YANDEX_CODE_PAGE, ReleaseGates.yandexRedirect(c))
        assertEquals("yx$fakeId", ReleaseGates.yandexScheme(c))
        assertEquals(false, fakeSecret in c.toString())
        dir.deleteRecursively()
    }

    @Test
    fun gradlePropertiesWinOverLocalProperties() {
        val dir = Files.createTempDirectory("gates").toFile()
        val local = File(dir, "local.properties").apply { writeText("${ReleaseGates.YANDEX_CLIENT_ID_PROPERTY}=${"a".repeat(32)}\n") }
        val c = ReleaseGates.yandexClient({ if (it == ReleaseGates.YANDEX_CLIENT_ID_PROPERTY) fakeId else null }, local)
        assertEquals(fakeId, c.clientId)
        dir.deleteRecursively()
    }

    @Test
    fun aMissingOrMalformedYandexClientFailsWithoutShowingTheSecret() {
        val none = ReleaseGates.YandexClient(null, null, null)
        val missing = assertNotNull(ReleaseGates.yandexClientProblem(none))
        assertTrue(ReleaseGates.YANDEX_CLIENT_ID_PROPERTY in missing && "local.properties" in missing, missing)
        assertNotNull(ReleaseGates.yandexClientProblem(ReleaseGates.YandexClient(fakeId, null, null)))
        val badId = "pasted-secret-in-the-id-key"
        val badIdProblem = assertNotNull(ReleaseGates.yandexClientProblem(ReleaseGates.YandexClient(badId, fakeSecret, null)))
        assertTrue(badId !in badIdProblem && ReleaseGates.YANDEX_CLIENT_ID_PROPERTY in badIdProblem, badIdProblem)
        val badSecret = "has spaces \" and quotes"
        val problem = assertNotNull(ReleaseGates.yandexClientProblem(ReleaseGates.YandexClient(fakeId, badSecret, null)))
        assertTrue(badSecret !in problem, problem)
        assertEquals("", ReleaseGates.yandexClientSecret(ReleaseGates.YandexClient(fakeId, badSecret, null)), "BuildConfig never gets it")
        assertEquals("yxunset", ReleaseGates.yandexScheme(none))
    }

    @Test
    fun onlyThisClientsAppRedirectIsTaken() {
        val good = ReleaseGates.YandexClient(fakeId, fakeSecret, "yx$fakeId:///")
        assertEquals("yx$fakeId:///", ReleaseGates.yandexRedirect(good))
        assertNull(ReleaseGates.yandexClientProblem(good))
        val other = ReleaseGates.YandexClient(fakeId, fakeSecret, "yx${"b".repeat(32)}:///")
        assertEquals(ReleaseGates.YANDEX_CODE_PAGE, ReleaseGates.yandexRedirect(other))
        assertNotNull(ReleaseGates.yandexClientProblem(other))
    }

    @Test
    fun aHostedHttpsAddressPasses() {
        assertNull(ReleaseGates.privacyUrlProblem("https://kinsight.app/privacy"))
        assertNull(ReleaseGates.privacyUrlProblem("https://someone.github.io/kinsight/privacy.html"))
    }

    @Test
    fun aMissingAddressFailsWithTheWayToSetIt() {
        for (missing in listOf(null, "", "   ")) {
            val problem = assertNotNull(ReleaseGates.privacyUrlProblem(missing))
            assertTrue("missing" in problem && ReleaseGates.PRIVACY_URL_PROPERTY in problem && ReleaseGates.PRIVACY_URL_ENV in problem, problem)
        }
    }

    @Test
    fun anyExampleAddressIsAPlaceholder() {
        for (placeholder in listOf("https://kinsight.example/privacy", "https://example.org/x", "https://www.EXAMPLE.com/p")) {
            assertTrue("placeholder" in assertNotNull(ReleaseGates.privacyUrlProblem(placeholder)), placeholder)
        }
    }

    @Test
    fun plainHttpNoHostAndGarbageFail() {
        assertTrue("https" in assertNotNull(ReleaseGates.privacyUrlProblem("http://kinsight.app/privacy")))
        assertTrue("host" in assertNotNull(ReleaseGates.privacyUrlProblem("https:///privacy")))
        assertTrue("valid" in assertNotNull(ReleaseGates.privacyUrlProblem("https://kinsight app/privacy")))
    }

    @Test
    fun theGradlePropertyWinsThenLocalPropertiesThenTheEnvironment() {
        val dir = Files.createTempDirectory("gates").toFile()
        val local = File(dir, "local.properties").apply { writeText("${ReleaseGates.PRIVACY_URL_PROPERTY}=https://local.app/p\n") }
        val none = File(dir, "absent.properties")
        assertEquals("https://prop.app/p", ReleaseGates.privacyUrl("https://prop.app/p", local, "https://env.app/p"))
        assertEquals("https://local.app/p", ReleaseGates.privacyUrl(null, local, "https://env.app/p"))
        assertEquals("https://env.app/p", ReleaseGates.privacyUrl(" ", none, "https://env.app/p"))
        assertNull(ReleaseGates.privacyUrl(null, none, null))
        dir.deleteRecursively()
    }

    @Test
    fun everyReleaseArtifactTaskIsGated() {
        val tasks = ReleaseGates.packagingTasks("release")
        assertTrue("assembleRelease" in tasks && "bundleRelease" in tasks && "packageRelease" in tasks, tasks.toString())
        assertTrue("packageReleaseBundle" in tasks && "signReleaseBundle" in tasks, tasks.toString())
    }
}
