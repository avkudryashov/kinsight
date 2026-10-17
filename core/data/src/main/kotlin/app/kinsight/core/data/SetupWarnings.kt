package app.kinsight.core.data

import app.kinsight.core.model.proto.StateEvent

/** A location problem the child's phone has open now, as its own signed events say (UI refresh §3, §6; spec §10). */
enum class SetupWarning { LOCATION_PERMISSION_OFF, LOCATION_DISABLED }

object SetupWarnings {
    /**
     * The problems still open in [own] (the child's own events): the newest LOCATION_PERMISSION_OFF or LOCATION_DISABLED
     * by seq whose end is not after its start (AlertPlanner.SELF_CLOSING: open when noticed, closed by a second event).
     * A permission the child never granted signs nothing (LocationWatch counts a revoke only after a grant), so a step
     * skipped at setup is not a warning here; the map says «ждём первую точку» instead.
     */
    fun of(own: List<EventView>): Set<SetupWarning> = buildSet {
        if (open(own, StateEvent.Kind.LOCATION_PERMISSION_OFF)) add(SetupWarning.LOCATION_PERMISSION_OFF)
        if (open(own, StateEvent.Kind.LOCATION_DISABLED)) add(SetupWarning.LOCATION_DISABLED)
    }

    private fun open(own: List<EventView>, kind: StateEvent.Kind): Boolean =
        own.filter { it.kind == kind }.maxByOrNull { it.seq }?.let { it.toMs <= it.fromMs } == true
}
