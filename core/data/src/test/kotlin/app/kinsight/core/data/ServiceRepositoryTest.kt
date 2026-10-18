package app.kinsight.core.data

import app.kinsight.core.transport.ble.CloudSync
import app.kinsight.core.transport.ble.ServiceSnapshot
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.transport.ble.SyncStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun theLastSyncIsTheNewerOfBluetoothAndTheDisk() = runBlocking<Unit> {
        val snapshot = MutableStateFlow(ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = 7L))
        val cloud = MutableStateFlow(CloudSync())
        val repo = ServiceRepository(snapshot, d.transport, { emptyList() }, d.sync, cloud = cloud)
        assertEquals(7L, repo.status.first().lastSyncMs)
        assertFalse(repo.status.first().cloudOn)
        cloud.value = CloudSync(on = true, lastSyncMs = 9L)
        val withCloud = repo.status.first { it.cloudOn }
        assertEquals(9L, withCloud.lastSyncMs, "a Disk sync after the last Bluetooth one is the last sync")
        snapshot.value = snapshot.value.copy(sync = SyncStatus(lastSyncWallMs = 12L))
        assertEquals(12L, repo.status.first { it.lastSyncMs == 12L }.lastSyncMs)
    }

    @Test
    fun syncNowAlsoAsksTheDisk() = runBlocking<Unit> {
        var asked = 0
        val repo = ServiceRepository(MutableStateFlow(ServiceSnapshot(ServiceState.RUNNING)), d.transport, { emptyList() }, d.sync, cloudNow = { asked++ })
        repo.syncNow()
        assertEquals(listOf("start:true"), d.sync.calls)
        assertEquals(1, asked)
    }

    @Test
    fun aHiddenNotificationIsPartOfTheStatus() = runBlocking<Unit> {
        val repo = ServiceRepository(MutableStateFlow(ServiceSnapshot(ServiceState.RUNNING)), d.transport, { emptyList() }, d.sync)
        assertFalse(repo.status.first().notificationHidden)
        d.transport.setNotificationHiddenSinceMs(5L)
        assertTrue(repo.status.first { it.notificationHidden }.notificationHidden)
        d.transport.setNotificationHiddenSinceMs(null)
        assertFalse(repo.status.first { !it.notificationHidden }.notificationHidden)
    }

    @Test
    fun usageAccessIsReadWithEveryStatus() = runBlocking<Unit> {
        val snapshot = MutableStateFlow(ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = null, sync = SyncStatus()))
        var granted = false
        val repo = ServiceRepository(snapshot, d.transport, { emptyList() }, d.sync, usageAccess = { granted })
        assertEquals(false, repo.status.first().usageAccessGranted)
        granted = true
        assertEquals(true, repo.status.first().usageAccessGranted, "a new collector re-reads it (the 30 s tick does the same)")
        assertEquals(true, ServiceRepository(snapshot, d.transport, { emptyList() }, d.sync).status.first().usageAccessGranted, "default: granted")
    }

    @Test
    fun refreshReReadsUsageAccessWithoutANewCollector() = runBlocking<Unit> {
        var granted = false
        val repo = ServiceRepository(MutableStateFlow(ServiceSnapshot(ServiceState.RUNNING)), d.transport, { emptyList() }, d.sync, usageAccess = { granted })
        val seen = Channel<Boolean>(Channel.UNLIMITED)
        val job = launch { repo.status.collect { seen.send(it.usageAccessGranted) } }
        withTimeout(10_000) { while (seen.receive()) Unit }
        granted = true
        repo.refresh()
        assertTrue(withTimeout(5_000) { seen.receive() }, "the same collector sees it at once, not after the 30 s tick")
        job.cancel()
    }
}
