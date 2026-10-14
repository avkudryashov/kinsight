package app.kinsight.core.transport.cloud

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
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
        try {
            val day = kid.author.summary("2026-10-13", 60_000)
            kid.publish(day)
            assertEquals(CloudResult.Synced(uploadedPacks = 1, received = 0), kid.sync(disk).run())
            assertIs<CloudResult.Synced>(mom.sync(disk).run())
            assertTrue(day in mom.store.liveEntries())
        } finally {
            disk.delete(CloudPaths.root(mom.store.groupId))
        }
    }
}
