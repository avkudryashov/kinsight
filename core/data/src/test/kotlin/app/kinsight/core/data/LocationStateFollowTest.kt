package app.kinsight.core.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationStateFollowTest {
    /** Emulator finding: access given back outside the app closes the problem and registers the alerts again. */
    @Test
    fun aRecordedEventAlsoRefreshesTheTracking() = runTest {
        val calls = mutableListOf<String>()
        suspend fun check(recorded: Boolean) =
            LocationStateFollow.after(recorded, refresh = { calls += "refresh" }, publish = { calls += "publish" }, upload = { calls += "upload" })
        check(recorded = false)
        assertEquals(emptyList(), calls, "an unchanged state does nothing")
        check(recorded = true)
        assertEquals(listOf("refresh", "publish", "upload"), calls)
    }
}
