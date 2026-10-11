package app.kinsight.feature.dashboard

import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.transport.ble.ServiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncAttentionTest {
    private val running = ServiceStatus(ServiceState.RUNNING, false, null, emptyList(), false, null)

    @Test
    fun usageAccessMattersOnlyWhereItIsNeeded() {
        val missing = running.copy(usageAccessGranted = false)
        assertEquals(SyncAttention.USAGE_ACCESS_MISSING, SyncAttention.of(missing, needsUsageAccess = true))
        assertNull(SyncAttention.of(missing, needsUsageAccess = false), "a parent or a child without consent collects nothing")
        assertNull(SyncAttention.of(running, needsUsageAccess = true))
    }

    @Test
    fun theUsersStopAndABlockedRadioComeFirst() {
        val missing = running.copy(usageAccessGranted = false)
        assertEquals(SyncAttention.STOPPED, SyncAttention.of(missing.copy(stoppedByUser = true), true))
        assertEquals(SyncAttention.NO_PERMISSION, SyncAttention.of(missing.copy(missingPermissions = listOf("p")), true))
        assertEquals(SyncAttention.BLUETOOTH_OFF, SyncAttention.of(missing.copy(state = ServiceState.BLUETOOTH_OFF), true))
        assertEquals(SyncAttention.KEY_ERROR, SyncAttention.of(missing.copy(state = ServiceState.STORAGE_ERROR), true))
        assertEquals(SyncAttention.USAGE_ACCESS_MISSING, SyncAttention.of(missing.copy(state = ServiceState.RADIO_ERROR), true), "a radio error retries by itself")
    }

    @Test
    fun missingUsageAccessComesBeforeAHiddenNotification() {
        val both = running.copy(usageAccessGranted = false, notificationHidden = true)
        assertEquals(SyncAttention.USAGE_ACCESS_MISSING, SyncAttention.of(both, true), "nothing is counted at all: the bigger gap first")
        assertEquals(SyncAttention.NOTIFICATION_HIDDEN, SyncAttention.of(both, false))
    }
}
