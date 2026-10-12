package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SyncService.requestFast on a running instance says true only when a fast window can really go out (final fix F3). */
class FastRequestTest {
    @Test
    fun aRunningOrStartingInstanceTakesTheFastWindow() {
        assertTrue(SyncService.acceptsFast(stopping = false, destroyed = false, state = ServiceState.RUNNING))
        assertTrue(SyncService.acceptsFast(stopping = false, destroyed = false, state = ServiceState.STARTING))
    }

    @Test
    fun aStoppingOrDestroyedInstanceRefuses() {
        assertFalse(SyncService.acceptsFast(stopping = true, destroyed = false, state = ServiceState.RUNNING))
        assertFalse(SyncService.acceptsFast(stopping = false, destroyed = true, state = ServiceState.RUNNING))
    }

    @Test
    fun aStateWhereFastModeIsANoOpRefuses() {
        val noOp = ServiceState.entries.filter { !SyncService.acceptsFast(stopping = false, destroyed = false, state = it) }.toSet()
        assertEquals(
            setOf(
                ServiceState.STOPPED, ServiceState.NO_GROUP, ServiceState.STORAGE_ERROR, ServiceState.NO_PERMISSION,
                ServiceState.BLUETOOTH_OFF, ServiceState.RADIO_ERROR,
            ),
            noOp,
        )
    }
}
