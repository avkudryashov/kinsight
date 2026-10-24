package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.proto.StateEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupWarningsTest {
    private val kid = DeviceId("cc".repeat(16))

    private fun ev(seq: Long, kind: StateEvent.Kind, from: Long, to: Long) = EventView(kid, seq, kind, from, to, from, 0)

    @Test
    fun anOpenProblemIsAWarning() {
        val open = ev(1, StateEvent.Kind.LOCATION_DISABLED, 1_000, 1_000)
        assertEquals(setOf(SetupWarning.LOCATION_DISABLED), SetupWarnings.of(listOf(open)))
    }

    @Test
    fun aClosedProblemIsNot() {
        val open = ev(1, StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, 1_000)
        val closed = ev(2, StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, 5_000)
        assertEquals(emptySet(), SetupWarnings.of(listOf(open, closed)))
    }

    /** Review Focus 5: the newest event by seq decides. */
    @Test
    fun aProblemClosedAndOpenedAgainIsAWarning() {
        val events = listOf(
            ev(1, StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, 1_000),
            ev(2, StateEvent.Kind.LOCATION_PERMISSION_OFF, 1_000, 5_000),
            ev(3, StateEvent.Kind.LOCATION_PERMISSION_OFF, 9_000, 9_000),
        )
        assertEquals(setOf(SetupWarning.LOCATION_PERMISSION_OFF), SetupWarnings.of(events.shuffled()))
    }

    @Test
    fun otherEventsAreNoWarning() {
        assertEquals(emptySet(), SetupWarnings.of(listOf(ev(1, StateEvent.Kind.BLUETOOTH_OFF, 1_000, 1_000))))
    }

    /** App blocking spec §5.4: the accessibility service off while rules need it. */
    @Test
    fun openBlockingOffIsAWarning() {
        val open = ev(1, StateEvent.Kind.BLOCKING_OFF, 1_000, 1_000)
        assertEquals(setOf(SetupWarning.BLOCKING_OFF), SetupWarnings.of(listOf(open)))
        assertEquals(emptySet(), SetupWarnings.of(listOf(open, ev(2, StateEvent.Kind.BLOCKING_OFF, 1_000, 5_000))))
    }
}
