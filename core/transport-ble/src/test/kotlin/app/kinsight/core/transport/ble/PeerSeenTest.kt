package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerSeenTest {
    private val dir = Files.createTempDirectory("kinsight-seen").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val state = TransportStateStore.create(File(dir, "t.preferences_pb"), scope)
    private val a = DeviceId("aa".repeat(16))
    private val b = DeviceId("bb".repeat(16))

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun peerSeenKeepsTheLatestPerPeer() = runBlocking<Unit> {
        state.setPeerSeen(a, 100)
        state.setPeerSeen(b, 50)
        state.setPeerSeen(a, 80) // older than what we have: ignored
        assertEquals(mapOf(a to 100L, b to 50L), state.peerSeen())
        assertEquals(mapOf(a to 100L, b to 50L), state.peerSeenFlow.first())
    }

    @Test
    fun clearForgetsEverything() = runBlocking<Unit> {
        state.setPeerSeen(a, 100)
        state.setStoppedByUser(true)
        state.setLastSyncMs(5)
        state.clear()
        assertTrue(state.peerSeen().isEmpty())
        assertFalse(state.stoppedByUserFlow.first())
        assertNull(state.lastSyncMs())
    }
}
