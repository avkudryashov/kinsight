package app.kinsight.feature.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchingTest {
    @Test
    fun searchingLastsUntilASyncNewerThanThePull() {
        assertEquals(false, stillSearching(sinceMs = null, lastSyncMs = null))
        assertEquals(true, stillSearching(sinceMs = 1_000, lastSyncMs = null))
        assertEquals(true, stillSearching(sinceMs = 1_000, lastSyncMs = 1_000), "the sync before the pull does not count")
        assertEquals(false, stillSearching(sinceMs = 1_000, lastSyncMs = 1_001))
    }
}
