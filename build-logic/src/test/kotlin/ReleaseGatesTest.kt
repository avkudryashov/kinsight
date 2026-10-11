import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseGatesTest {
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
