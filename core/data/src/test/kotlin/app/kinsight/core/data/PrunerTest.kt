package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.locationEntry
import app.kinsight.core.model.proto.Location
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.summary
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PrunerTest {
    private companion object {
        const val DAY = 86_400_000L
    }

    private val d = TestDevice()

    @AfterTest
    fun cleanUp() = d.close()

    /** Pruner prunes the event history with the settings' summary retention, measured from its injected clock (A-6). */
    @Test
    fun pruneUsesTheSettingsRetentionAndTheInjectedClock() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val store = d.store()
        val kid = EntryAuthor(store.groupId, DeviceKeys.generate(), lastSeq = 0)
        fun bt(daysAgo: Long) = d.now - daysAgo * DAY
        val events = listOf(200L, 100L, 10L).map { ago ->
            kid.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.BLUETOOTH_OFF, from_ms = bt(ago), to_ms = bt(ago))) }
        }
        store.applyBatch(events)
        // d.now lies a year before the wall clock: with System.currentTimeMillis the 200 day old event would go now.
        val pruner = Pruner(d.db, d.groups, d.settings, d.keyProvider, clock = { d.now })

        pruner.prune() // default summary retention: 365 days
        assertEquals(events, store.stateEvents())

        d.settings.setRetention(detailDays = 30, summaryDays = 90)
        pruner.prune()
        assertEquals(events.takeLast(1), store.stateEvents())
    }

    /**
     * Spec §8: LOCATION points expire 48 h after their fix time, measured on the Pruner's injected clock (Y4 Task 2
     * review), and usage days on the date of that clock. d.now lies a year before the wall clock: on the wall clock every
     * entry here would be long expired.
     */
    @Test
    fun locationAndUsageDaysExpireOnTheInjectedClock() = runBlocking<Unit> {
        d.group.createGroup("Mom")
        val store = d.store()
        val kid = EntryAuthor(store.groupId, DeviceKeys.generate(), lastSeq = 0)
        val hour = 3_600_000L
        val fresh = kid.locationEntry(404_168_000, -37_038_000, 10, d.now - hour, Location.Provider.GPS)
        val stale = kid.locationEntry(404_168_000, -37_038_000, 10, d.now - 49 * hour, Location.Provider.GPS)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(d.now), ZoneId.systemDefault())
        val day = kid.summary(today.minusDays(10).toString(), 60_000)
        store.applyBatch(listOf(stale, fresh, day))
        Pruner(d.db, d.groups, d.settings, d.keyProvider, clock = { d.now }).prune()
        assertEquals(listOf(fresh), store.locations())
        assertEquals(listOf(day), store.summaries(), "ten days old on the injected clock")
    }
}
