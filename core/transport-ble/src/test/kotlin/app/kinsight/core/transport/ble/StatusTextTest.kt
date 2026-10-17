package app.kinsight.core.transport.ble

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member
import app.kinsight.core.model.proto.DeviceRole
import okio.ByteString
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTextTest {
    private val syncing = SyncStatus(syncing = true, lastSyncWallMs = 7)

    @Test
    fun storageErrorWinsOverEverything() {
        val line = StatusText.line(ServiceSnapshot(ServiceState.STORAGE_ERROR, "lost choice", syncing, cloudOn = true, cloudLastSyncMs = 9))
        assertEquals(StatusLine(StatusLine.Kind.STORAGE_ERROR, 9), line)
    }

    @Test
    fun withoutTheCloudABrokenBluetoothIsTheProblem() {
        assertEquals(StatusLine.Kind.NO_PERMISSION, StatusText.line(ServiceSnapshot(ServiceState.NO_PERMISSION, sync = syncing)).kind)
        assertEquals(StatusLine.Kind.BLUETOOTH_OFF, StatusText.line(ServiceSnapshot(ServiceState.BLUETOOTH_OFF, sync = syncing)).kind)
        assertEquals(StatusLine.Kind.STARTING, StatusText.line(ServiceSnapshot(ServiceState.STARTING)).kind)
    }

    @Test
    fun withTheCloudOnABrokenBluetoothIsNoProblem() {
        for (state in listOf(ServiceState.NO_PERMISSION, ServiceState.BLUETOOTH_OFF, ServiceState.RADIO_ERROR)) {
            val s = ServiceSnapshot(state, lastSyncWallMs = 5, cloudOn = true, cloudLastSyncMs = 9)
            assertEquals(StatusLine(StatusLine.Kind.SYNCED, 9), StatusText.line(s), "$state")
            assertEquals(StatusLine.Kind.NEVER_SYNCED, StatusText.line(ServiceSnapshot(state, cloudOn = true)).kind, "$state")
        }
    }

    @Test
    fun radioErrorIsItsOwnLineNotBluetoothOff() {
        val line = StatusText.line(ServiceSnapshot(ServiceState.RADIO_ERROR, "cannot open servers", syncing))
        assertEquals(StatusLine(StatusLine.Kind.RADIO_ERROR, 7), line)
    }

    @Test
    fun runningSaysWhenItLastSyncedOrThatAPhoneNeedsAnUpdate() {
        assertEquals(StatusLine(StatusLine.Kind.SYNCED, 7), StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = syncing)))
        val outdated = SyncStatus(lastProblem = SyncProblem.PEER_NEEDS_UPDATE)
        assertEquals(StatusLine.Kind.PEER_NEEDS_UPDATE, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = outdated)).kind)
        val failed = SyncStatus(lastProblem = SyncProblem.LINK_FAILED)
        assertEquals(StatusLine.Kind.NEVER_SYNCED, StatusText.line(ServiceSnapshot(ServiceState.RUNNING, sync = failed)).kind)
        val cloudOnly = ServiceSnapshot(ServiceState.RUNNING, sync = failed, cloudOn = true, cloudLastSyncMs = 3)
        assertEquals(StatusLine(StatusLine.Kind.SYNCED, 3), StatusText.line(cloudOnly), "a Disk sync counts as a sync")
    }

    @Test
    fun theLastSyncIsTheNewerOfBothPaths() {
        assertEquals(null, LastSync.of(bluetoothMs = null, cloudMs = null))
        assertEquals(5L, LastSync.of(bluetoothMs = 5, cloudMs = null))
        assertEquals(9L, LastSync.of(bluetoothMs = null, cloudMs = 9))
        assertEquals(9L, LastSync.of(bluetoothMs = 5, cloudMs = 9))
        assertEquals(12L, LastSync.of(bluetoothMs = 12, cloudMs = 9))
    }

    @Test
    fun aParentsNotificationIsOneLine() {
        val parent = ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = 5, peers = listOf("Kid"), peersAreParents = false, cloudOn = true, cloudLastSyncMs = 9)
        assertEquals(NotificationContent(NotificationPart.Status(StatusLine(StatusLine.Kind.SYNCED, 9)), emptyList(), stop = true), StatusText.content(parent))
        // Bluetooth off and no cloud: the problem is the one line.
        val off = parent.copy(state = ServiceState.BLUETOOTH_OFF, cloudOn = false, cloudLastSyncMs = null)
        assertEquals(NotificationPart.Status(StatusLine(StatusLine.Kind.BLUETOOTH_OFF, 5)), StatusText.content(off).title)
        assertEquals(emptyList(), StatusText.content(off).lines)
        val broken = parent.copy(state = ServiceState.STORAGE_ERROR)
        assertEquals(StatusLine.Kind.STORAGE_ERROR, (StatusText.content(broken).title as NotificationPart.Status).line.kind)
    }

    @Test
    fun aChildsNotificationKeepsItsTitleAndSaysWhenItSynced() {
        val child = ServiceSnapshot(ServiceState.RUNNING, lastSyncWallMs = 5, peers = listOf("Dad", "Mom"), peersAreParents = true, cloudOn = true, cloudLastSyncMs = 9)
        val content = StatusText.content(child)
        assertEquals(NotificationPart.ChildTitle, content.title)
        assertEquals(
            listOf(NotificationPart.Status(StatusLine(StatusLine.Kind.SYNCED, 9)), NotificationPart.Peers(StatusText.PeerLine.VISIBLE_TO, listOf("Dad", "Mom"))),
            content.lines,
        )
        assertFalse(content.stop)
        val paused = StatusText.content(child.copy(sharingPaused = true)).lines
        assertEquals(NotificationPart.Paused, paused[1])
    }

    @Test
    fun aSyncTodayShowsTheTimeOnlyAndAnOlderOneTheDateToo() {
        val zone = ZoneId.of("Europe/Madrid")
        val now = ZonedDateTime.of(2026, 10, 17, 15, 45, 0, 0, zone).toInstant().toEpochMilli()
        val earlier = ZonedDateTime.of(2026, 10, 17, 0, 1, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 10, 16, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(StatusText.sameDay(earlier, now, zone))
        assertFalse(StatusText.sameDay(yesterday, now, zone))
    }

    /** Fix round: at local midnight the line is rebuilt, so yesterday's «в 15:41» gets its date. */
    @Test
    fun theLineIsRebuiltAtTheNextLocalMidnight() {
        val zone = ZoneId.of("Europe/Madrid")
        val now = ZonedDateTime.of(2026, 10, 17, 23, 59, 30, 0, zone).toInstant().toEpochMilli()
        assertEquals(30_000L, StatusText.untilNextMidnightMs(now, zone))
        val midnight = ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(24 * 3_600_000L, StatusText.untilNextMidnightMs(midnight, zone), "exactly at midnight: the next one")
        // 25 October 2026: clocks go back in Madrid, that day lasts 25 hours.
        val dstDay = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(25 * 3_600_000L, StatusText.untilNextMidnightMs(dstDay, zone))
    }

    @Test
    fun aParentGetsTheQuietChannelAndAChildKeepsTheServiceOne() {
        assertEquals(ServiceChannels.PARENT, ServiceChannels.forRole(peersAreParents = false, parentChannelExists = false))
        assertEquals(ServiceChannels.CHILD, ServiceChannels.forRole(peersAreParents = true, parentChannelExists = true))
        // Unknown role (first post): the channel this phone already uses, the service one on a fresh install.
        assertEquals(ServiceChannels.PARENT, ServiceChannels.forRole(peersAreParents = null, parentChannelExists = true))
        assertEquals(ServiceChannels.CHILD, ServiceChannels.forRole(peersAreParents = null, parentChannelExists = false))
        assertEquals(ServiceChannels.CHILD, ServiceChannels.retired(peersAreParents = false))
        assertEquals(ServiceChannels.PARENT, ServiceChannels.retired(peersAreParents = true))
        assertEquals(null, ServiceChannels.retired(peersAreParents = null))
        assertEquals("kinsight-service", ServiceChannels.CHILD, "the child's channel keeps its id, so its settings stay")
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
        val parent = ServiceSnapshot(ServiceState.RUNNING, peers = listOf("Kid"), peersAreParents = false)
        assertFalse(StatusText.childPhone(parent))
        assertTrue(StatusText.offersStop(parent))
    }

    /** Final review Important 1: until the role is read (first post, Bluetooth off, no permission, storage error) it is a child's. */
    @Test
    fun anUnknownRoleFailsClosedAsAChildsPhone() {
        for (state in listOf(ServiceState.STARTING, ServiceState.BLUETOOTH_OFF, ServiceState.NO_PERMISSION, ServiceState.STORAGE_ERROR)) {
            val unknown = ServiceSnapshot(state)
            assertTrue(StatusText.childPhone(unknown), "$state")
            assertFalse(StatusText.offersStop(unknown), "$state: no Stop before the role is known")
        }
        assertTrue(StatusText.offersStop(ServiceSnapshot(ServiceState.BLUETOOTH_OFF, peersAreParents = false)))
    }

    @Test
    fun theRoleIsUnknownUntilThisPhoneIsAnActiveMember() {
        fun member(hex: String, child: Boolean) = Member(
            DeviceId(hex.repeat(16)), ByteString.EMPTY, ByteString.EMPTY, "x", isAdmin = false,
            role = if (child) DeviceRole.CHILD else DeviceRole.PARENT,
        )
        val kid = member("cc", child = true)
        val mom = member("aa", child = false)
        val active = listOf(kid, mom).associateBy { it.id }
        assertEquals(true, StatusText.selfIsChild(active, kid.id))
        assertEquals(false, StatusText.selfIsChild(active, mom.id))
        assertEquals(null, StatusText.selfIsChild(active, DeviceId("dd".repeat(16))))
        assertEquals(null, StatusText.selfIsChild(null, kid.id))
    }
}
