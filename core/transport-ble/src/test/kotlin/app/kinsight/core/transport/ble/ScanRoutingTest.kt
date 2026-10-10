package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanRoutingTest {
    @Test
    fun aRunningEngineGetsTheBatch() {
        assertEquals(ScanRouting.Action.DELIVER, ScanRouting.route(engineRunning = true, serviceRunning = true))
    }

    @Test
    fun aServiceWithoutRadioDropsTheBatchButKeepsTheScan() {
        // Starting up or idle on a problem: the service owns the scan (close() stops it), nothing to stop here.
        assertEquals(ScanRouting.Action.DROP, ScanRouting.route(engineRunning = false, serviceRunning = true))
    }

    @Test
    fun noServiceMeansAnOrphanScanToStop() {
        // The PendingIntent scan survived the process: stop it instead of waking the app for nothing.
        assertEquals(ScanRouting.Action.STOP_ORPHAN_SCAN, ScanRouting.route(engineRunning = false, serviceRunning = false))
    }
}
