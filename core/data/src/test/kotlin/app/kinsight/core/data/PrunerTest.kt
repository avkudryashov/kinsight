package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.runBlocking
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
}
