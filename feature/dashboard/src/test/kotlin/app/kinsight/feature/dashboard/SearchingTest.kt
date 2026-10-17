package app.kinsight.feature.dashboard

import app.kinsight.core.transport.ble.LastSync
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchingTest {
    @Test
    fun searchingLastsUntilASyncNewerThanThePull() {
        assertEquals(null, searchLine(sinceMs = null, lastSyncMs = null, cloudOn = false))
        assertEquals(SearchLine.NEARBY, searchLine(sinceMs = 1_000, lastSyncMs = null, cloudOn = false))
        assertEquals(SearchLine.NEARBY, searchLine(sinceMs = 1_000, lastSyncMs = 1_000, cloudOn = false), "the sync before the pull does not count")
        assertEquals(null, searchLine(sinceMs = 1_000, lastSyncMs = 1_001, cloudOn = false))
    }

    @Test
    fun withTheCloudOnItIsUpdatingUntilEitherPathFinishes() {
        assertEquals(SearchLine.UPDATING, searchLine(sinceMs = 1_000, lastSyncMs = LastSync.of(bluetoothMs = 900, cloudMs = 950), cloudOn = true))
        // A Bluetooth session done after the pull ends it, and so does a Disk run.
        assertEquals(null, searchLine(sinceMs = 1_000, lastSyncMs = LastSync.of(bluetoothMs = 1_200, cloudMs = 950), cloudOn = true))
        assertEquals(null, searchLine(sinceMs = 1_000, lastSyncMs = LastSync.of(bluetoothMs = 900, cloudMs = 1_100), cloudOn = true))
        assertEquals(null, searchLine(sinceMs = null, lastSyncMs = null, cloudOn = true))
    }

    @Test
    fun withTheCloudOffOnlyBluetoothIsLookingForPhonesNearby() {
        assertEquals(SearchLine.NEARBY, searchLine(sinceMs = 1_000, lastSyncMs = LastSync.of(bluetoothMs = 900, cloudMs = null), cloudOn = false))
        assertEquals(null, searchLine(sinceMs = 1_000, lastSyncMs = LastSync.of(bluetoothMs = 1_001, cloudMs = null), cloudOn = false))
    }
}
