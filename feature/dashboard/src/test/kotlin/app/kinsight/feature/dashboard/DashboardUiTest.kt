package app.kinsight.feature.dashboard

import app.kinsight.core.data.DashboardState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What DashboardViewModel shows, decided outside Compose. */
class DashboardUiTest {
    private val mom = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = true, lastSeenMs = null)

    /** Y5 Task 5 review Minor 1: an adult with an outdated consent record (a parent once consented as a child) sees no card. */
    @Test
    fun anAdultNeverSeesTheReconsentCard() {
        val adult = dashboardUi(DashboardState(mom, null, emptyList(), emptyList()), null, 24, false, ack = null, consentOutdated = true)
        assertFalse(adult.consentOutdated)
        assertEquals(emptyList(), adult.newParents)
        val child = dashboardUi(DashboardState(kid, null, emptyList(), listOf(mom.copy(isSelf = false))), null, 24, false, ack = null, consentOutdated = true)
        assertTrue(child.consentOutdated)
        assertFalse(dashboardUi(null, null, 24, false, ack = null, consentOutdated = true).consentOutdated, "nothing loaded: no card")
    }
}
