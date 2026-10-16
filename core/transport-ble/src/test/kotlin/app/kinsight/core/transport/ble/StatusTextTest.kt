package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTextTest {
    private val syncing = SyncStatus(syncing = true, lastSyncWallMs = 7)

    @Test
    fun storageErrorWinsOverEverything() {
        val line = StatusText.line(ServiceSnapshot(ServiceState.STORAGE_ERROR, "lost choice", syncing))
        assertEquals(StatusLine(StatusLine.Kind.STORAGE_ERROR, 7), line)
    }

    @Test
    fun environmentProblemsComeBeforeSyncState() {
        assertEquals(StatusLine.Kind.NO_PERMISSION, StatusText.line(ServiceSnapshot(ServiceState.NO_PERMISSION, sync = syncing)).kind)
        assertEquals(StatusLine.Kind.BLUETOOTH_OFF, StatusText.line(ServiceSnapshot(ServiceState.BLUETOOTH_OFF, sync = syncing)).kind)
        assertEquals(StatusLine.Kind.STARTING, StatusText.line(ServiceSnapshot(ServiceState.STARTING)).kind)
    }

    @Test
    fun radioErrorIsItsOwnLineNotBluetoothOff() {
        val line = StatusText.line(ServiceSnapshot(ServiceState.RADIO_ERROR, "cannot open servers", syncing))
        assertEquals(StatusLine(StatusLine.Kind.RADIO_ERROR, 7), line)
    }

    @Test
    fun runningShowsSyncingThenUpdateHintThenWaiting() {
        assertEquals(StatusLine.Kind.SYNCING, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = syncing)).kind)
        val outdated = SyncStatus(lastProblem = SyncProblem.PEER_NEEDS_UPDATE)
        assertEquals(StatusLine.Kind.PEER_NEEDS_UPDATE, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = outdated)).kind)
        val failed = SyncStatus(lastProblem = SyncProblem.LINK_FAILED)
        assertEquals(StatusLine.Kind.WAITING, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = failed)).kind)
    }

    @Test
    fun aPausedChildSaysSoAndAParentNeverDoes() {
        val child = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("Mom"), peersAreParents = true)
        assertEquals(StatusText.PeerLine.VISIBLE_TO, StatusText.peerLine(child))
        assertEquals(false, StatusText.showsPause(child))
        assertEquals(true, StatusText.showsPause(child.copy(sharingPaused = true)))
        // A parent shares nothing: it syncs with the others, and has no pause to tell.
        val parent = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("Kid"), peersAreParents = false, sharingPaused = true)
        assertEquals(StatusText.PeerLine.SYNCS_WITH, StatusText.peerLine(parent))
        assertEquals(false, StatusText.showsPause(parent))
        assertEquals(null, StatusText.peerLine(parent.copy(peers = emptyList())))
    }

    @Test
    fun lastSyncFallsBackToThePersistedValue() {
        assertEquals(5L, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = 5)).lastSyncWallMs)
        assertEquals(9L, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = SyncStatus(lastSyncWallMs = 9), lastSyncWallMs = 5)).lastSyncWallMs)
    }

    @Test
    fun peerNamesListTheOthersSortedWithoutSelf() {
        fun member(hexDigit: Char, name: String) =
            Member(DeviceId(hexDigit.toString().repeat(32)), ByteString.EMPTY, ByteString.EMPTY, name, isAdmin = false)
        val self = member('a', "Me")
        val members = listOf(member('c', "Masha"), self, member('b', "Dad"), member('d', "Masha"))
        assertEquals(listOf("Dad", "Masha", "Masha"), StatusText.peerNames(members, self.id))
        assertEquals(emptyList(), StatusText.peerNames(listOf(self), self.id))
    }

    @Test
    fun aChildsNotificationNamesTheFamilyGroupAndOffersNoStop() {
        val child = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("Mom"), peersAreParents = true)
        assertTrue(StatusText.childPhone(child))
        assertFalse(StatusText.offersStop(child), "spec §10: only a parent stops it, with the PIN in Settings")
        val parent = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("Kid"))
        assertFalse(StatusText.childPhone(parent))
        assertTrue(StatusText.offersStop(parent))
    }
}
