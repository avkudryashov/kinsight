package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
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
import kotlin.test.assertNull

class CloudStateStoreTest {
    private val dir = Files.createTempDirectory("kinsight-cloud-state").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = CloudStateStore.create(File(dir, "c.preferences_pb"), scope)

    @AfterTest
    fun cleanUp() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun aSyncRecordsItsGenerationAndTheRefreshCheckIsKept() = runBlocking<Unit> {
        store.record(10, synced = true, problem = null, generation = 2)
        assertEquals(2L, store.current().syncedGeneration)
        store.record(20, synced = false, problem = CloudProblem.NETWORK, generation = 3)
        assertEquals(2L, store.current().syncedGeneration, "only a sync moves it")
        store.markRefreshCheck(30)
        assertEquals(30L, store.current().refreshCheckMs)
        store.clear()
        assertEquals(CloudState(), store.current())
    }

    /** Rulings Q14: the owner is heard on this device's clock, when its newest seq first grows here. */
    @Test
    fun theOwnerIsHeardWhenItsNewestSeqGrows() = runBlocking<Unit> {
        val mom = DeviceKeys.generate().deviceId
        val dad = DeviceKeys.generate().deviceId
        assertNull(store.current().ownerHeardAtMs)
        store.observeOwner(mom, newestSeq = 5, nowMs = 100)
        assertEquals(100L, store.current().ownerHeardAtMs, "first tracked: the week starts now")
        store.observeOwner(mom, newestSeq = 5, nowMs = 200)
        assertEquals(100L, store.current().ownerHeardAtMs, "nothing new from the owner")
        store.observeOwner(mom, newestSeq = 7, nowMs = 300)
        assertEquals(300L, store.current().ownerHeardAtMs)
        assertEquals(300L, store.current().ownerHeardFrom(mom))
        assertNull(store.current().ownerHeardFrom(dad), "another owner was never heard here")
        store.observeOwner(dad, newestSeq = 3, nowMs = 400)
        assertEquals(400L, store.current().ownerHeardFrom(dad), "a new owner is tracked from now")
        store.record(500, synced = true, problem = null, generation = 1)
        assertEquals(400L, store.current().ownerHeardAtMs, "a run record keeps it")
        store.clear()
        assertNull(store.current().ownerHeardAtMs)
    }
}
