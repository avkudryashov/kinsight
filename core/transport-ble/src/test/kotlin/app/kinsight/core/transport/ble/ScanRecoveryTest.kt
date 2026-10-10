package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanRecoveryTest {
    @Test
    fun anAsyncErrorAfterRc0KeepsTheBackoffGrowing() {
        // PendingIntent scans: startScan says 0, the failure arrives later as EXTRA_ERROR_CODE.
        val r = ScanRecovery()
        val waits = List(5) {
            assertNull(r.onStarted(rc = 0))
            r.onError()
        }
        assertEquals(listOf(5_000L, 30_000L, 120_000L, 900_000L, 900_000L), waits)
    }

    @Test
    fun aGoodDeliveryResetsTheBackoff() {
        val r = ScanRecovery()
        r.onStarted(rc = 0)
        r.onError()
        r.onStarted(rc = 0)
        r.onError()
        r.onDelivered()
        assertEquals(5_000L, r.onError())
    }

    @Test
    fun aFailedStartBacksOffLikeAnError() {
        val r = ScanRecovery()
        assertEquals(5_000L, r.onStarted(rc = 6))
        assertEquals(30_000L, r.onError())
        r.reset()
        assertEquals(5_000L, r.onStarted(rc = -1))
    }
}
