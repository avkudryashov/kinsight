package app.kinsight.core.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerStoreTest {
    private val dir = Files.createTempDirectory("kinsight-location").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = DataStoreTrackerStore.create(File(dir, "l.preferences_pb"), scope)

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun theStateRoundTripsAndClears() = runBlocking<Unit> {
        assertEquals(TrackerState(), store.load())
        val s = TrackerState(Mode.OUTSIDE, setOf("aa".repeat(16)), setOf("aa".repeat(16) + ":1:2:150"), nextTickMs = 42)
        store.save(s)
        assertEquals(s, store.load())
        store.save(s.copy(nextTickMs = null))
        assertEquals(null, store.load().nextTickMs)
        store.clear()
        assertEquals(TrackerState(), store.load())
    }
}
