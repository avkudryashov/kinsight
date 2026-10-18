package app.kinsight.debug

import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.location.LocationDebugStatus
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.location.Mode
import app.kinsight.core.transport.ble.GateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DebugLocationTest {
    @Test
    fun zoneExtrasBecomeADraftInDegreesTimesTenToTheSeven() {
        assertEquals(ZoneDraft(null, "Home", 404_168_000, -37_038_000, 150), DebugLocation.draft("40.4168", "-3.7038", 150, "Home"))
        assertFailsWith<IllegalStateException> { DebugLocation.draft(null, "-3.7038", 150, "Home") }
        assertFailsWith<IllegalStateException> { DebugLocation.draft("40.4168", "west", 150, "Home") }
        assertFailsWith<IllegalStateException> { DebugLocation.draft("NaN", "-3.7038", 150, "Home") }
        assertFailsWith<IllegalArgumentException> { DebugLocation.draft("40.4168", "-3.7038", 0, "Home") }
        assertFailsWith<IllegalStateException> { DebugLocation.draft("40.4168", "-3.7038", 150, null) }
    }

    @Test
    fun theStatusLineHasCountsAndTimesButNoCoordinates() {
        val tracker = LocationDebugStatus(Mode.OUTSIDE, serviceForeground = true, serviceStartsPending = 0, nextTickMs = 1_300_000L, registeredZones = 2)
        val problems = DebugLocation.problems(null, 5L, null, null, 7L)
        assertEquals(listOf("NOTIFICATION_HIDDEN", "LOCATION_DISABLED"), problems)
        val line = DebugLocation.statusLine(tracker, GateState.Open(1L), 2, LocationPermissions.State.ALWAYS, true, problems, nowMs = 1_000_000L)
        assertEquals(
            "gate=open zones=2 mode=OUTSIDE service=running serviceStartsPending=0 nextTick=1300000(in 300s) registered=2 " +
                "access=ALWAYS systemLocation=true problems=NOTIFICATION_HIDDEN,LOCATION_DISABLED",
            line,
        )
        assertEquals(
            "gate=closed zones=none mode=none service=none nextTick=none registered=0 access=NONE systemLocation=unknown problems=none",
            DebugLocation.statusLine(null, GateState.Closed, null, LocationPermissions.State.NONE, null, emptyList(), 0L),
        )
        assertFalse("40.4168" in line || "404168" in line)
    }
}
