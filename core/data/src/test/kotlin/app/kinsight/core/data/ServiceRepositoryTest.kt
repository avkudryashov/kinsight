package app.kinsight.core.data

import app.kinsight.core.transport.ble.ServiceSnapshot
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceRepositoryTest {
    private val d = TestDevice()

    @AfterTest
    fun cleanUp() = d.close()

    @Test
    fun statusCarriesTheSessionCounterAndControlsTheService() = runBlocking<Unit> {
        val snapshot = MutableStateFlow(
            ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = 7L, sync = SyncStatus(finishedSessions = 2, lastSyncWallMs = null)),
        )
        val repo = ServiceRepository(snapshot, d.transport, { listOf("p") }, d.sync)
        d.transport.setStoppedByUser(true)
        assertEquals(ServiceStatus(ServiceState.RUNNING, true, 7L, listOf("p"), false, null, finishedSessions = 2), repo.status.first())
        snapshot.value = snapshot.value.copy(sync = SyncStatus(finishedSessions = 3, lastSyncWallMs = 9L))
        assertEquals(3, repo.status.first { it.finishedSessions == 3 }.finishedSessions)
        assertEquals(9L, repo.status.first().lastSyncMs)
        repo.start()
        repo.stop()
        repo.syncNow()
        assertEquals(listOf("startByUser", "requestStop", "start:true"), d.sync.calls)
    }
}
