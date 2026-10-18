package app.kinsight.core.model

import app.kinsight.core.model.proto.StateEvent

/** Values the entry format fixes, shared by the modules that write and read them (audit A-17: one definition each). */
object WireConstants {
    /**
     * The remainder bucket of UsageSummary/UsageDetail apps: the time of apps beyond the listed ones. Never an app:
     * Android package names allow only letters, digits, '_' and '.', so '~' cannot occur in one.
     */
    const val REMAINDER_PACKAGE = "~other"

    /** The StateEvents of the child's open pause; the last one says whether this device shares. */
    val SHARING_KINDS: Set<StateEvent.Kind> = setOf(StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.SHARING_RESUMED)

    /** The StateEvents of a safe zone (spec §8.1); each names its zone in StateEvent.zone_id. */
    val ZONE_KINDS: Set<StateEvent.Kind> = setOf(StateEvent.Kind.ZONE_ENTER, StateEvent.Kind.ZONE_EXIT)
}
