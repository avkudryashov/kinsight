package app.kinsight.core.data

import app.kinsight.core.data.SetupItem.BATTERY
import app.kinsight.core.data.SetupItem.LOCATION
import app.kinsight.core.data.SetupItem.LOCATION_BACKGROUND
import app.kinsight.core.data.SetupItem.USAGE_ACCESS
import app.kinsight.core.location.LocationPermissions.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChildSetupTest {
    @Test
    fun nothingGrantedAsksEverythingInOrder() {
        assertEquals(listOf(USAGE_ACCESS, LOCATION, LOCATION_BACKGROUND, BATTERY), ChildSetup.pending(false, State.NONE, false))
    }

    @Test
    fun grantedStepsAreNotAskedAgain() {
        assertEquals(emptyList(), ChildSetup.pending(true, State.ALWAYS, true))
        assertEquals(listOf(LOCATION_BACKGROUND), ChildSetup.pending(true, State.WHILE_IN_USE, true))
    }

    @Test
    fun approximateOnlyStillAsksForPrecise() {
        assertEquals(listOf(LOCATION, LOCATION_BACKGROUND), ChildSetup.pending(true, State.COARSE_ONLY, true))
    }

    /** Review Focus 4: no background step while precise location is still missing. */
    @Test
    fun laterSkipsAStepAndTheBackgroundWaitsForPrecise() {
        val all = ChildSetup.pending(false, State.NONE, false)
        assertEquals(USAGE_ACCESS, ChildSetup.next(all, emptySet()))
        assertEquals(LOCATION, ChildSetup.next(all, setOf(USAGE_ACCESS)))
        assertEquals(BATTERY, ChildSetup.next(all, setOf(USAGE_ACCESS, LOCATION)))
        assertNull(ChildSetup.next(all, setOf(USAGE_ACCESS, LOCATION, BATTERY)))
        assertEquals(LOCATION_BACKGROUND, ChildSetup.next(ChildSetup.pending(true, State.WHILE_IN_USE, true), emptySet()))
    }
}
