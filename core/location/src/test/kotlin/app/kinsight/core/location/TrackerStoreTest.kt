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

    /** Y4 parked minor: an unreadable file starts over from the empty state (logged), so tracking can still be turned off. */
    @Test
    fun aCorruptFileStartsOver() = runBlocking<Unit> {
        val file = File(dir, "bad.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04))
        val lines = mutableListOf<String>()
        val bad = DataStoreTrackerStore.create(file, scope) { lines += it }
        assertEquals(TrackerState(), bad.load())
        bad.save(TrackerState(Mode.OUTSIDE))
        assertEquals(Mode.OUTSIDE, bad.load().mode)
        assertEquals(1, lines.size)
    }
}
