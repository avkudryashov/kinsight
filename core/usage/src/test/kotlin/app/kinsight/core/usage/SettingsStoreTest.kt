package app.kinsight.core.usage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsStoreTest {
    private val dir = Files.createTempDirectory("kinsight-settings").toFile()
    private val file = File(dir, "usage.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): SettingsStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return SettingsStore.create(file, scope)
    }

    @AfterTest
    fun cleanUp() {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        dir.deleteRecursively()
    }

    @Test
    fun defaultsAreSharingOffAndSpecRetention() = runTest {
        assertEquals(UsageSettings(sharing = false, detailRetentionDays = 90, summaryRetentionDays = 365), store().current())
    }

    @Test
    fun valuesPersistAcrossInstances() = runTest {
        val first = store()
        first.setSharing(true)
        first.setRetention(30, 200)
        // DataStore allows one active instance per file: finish the first before opening the second.
        scopes.removeLast().coroutineContext.job.cancelAndJoin()
        assertEquals(UsageSettings(sharing = true, detailRetentionDays = 30, summaryRetentionDays = 200), store().current())
    }

    @Test
    fun rejectsInvalidRetention() = runTest {
        val s = store()
        assertFailsWith<IllegalArgumentException> { s.setRetention(0, 10) }
        assertFailsWith<IllegalArgumentException> { s.setRetention(100, 50) }
        assertFailsWith<IllegalArgumentException> { s.setRetention(10, 4000) }
        assertEquals(UsageSettings(), s.current())
    }
}
