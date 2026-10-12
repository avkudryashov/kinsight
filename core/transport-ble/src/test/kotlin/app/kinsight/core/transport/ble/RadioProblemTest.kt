package app.kinsight.core.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioProblemTest {
    @Test
    fun theDetailIsTheTechnicalLineForSupport() {
        assertEquals("advertising failed (2), retrying", RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = 2).detail)
        assertEquals("advertising failed (3)", RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, code = 3).detail)
        assertEquals("advertising failed: SecurityException", RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, cause = "SecurityException").detail)
        assertEquals("scan failed (error 2)", RadioProblem(RadioProblem.Kind.SCAN_FAILED, code = 2).detail)
        assertEquals("scan start failed (rc 6)", RadioProblem(RadioProblem.Kind.SCAN_START_FAILED, code = 6).detail)
        assertEquals("pairing GATT service failed: refused", RadioProblem(RadioProblem.Kind.PAIRING_SERVICE_FAILED, cause = "refused").detail)
        assertEquals("pairing GATT service failed (133)", RadioProblem(RadioProblem.Kind.PAIRING_SERVICE_FAILED, code = 133).detail)
    }

    @Test
    fun severalStandingProblemsJoinIntoOneDetail() {
        val scan = RadioProblem(RadioProblem.Kind.SCAN_FAILED, code = 2)
        val advert = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = 2)
        assertEquals("scan failed (error 2); advertising failed (2), retrying", RadioProblem.detailOf(listOf(scan, advert)))
        assertEquals(null, RadioProblem.detailOf(emptyList()))
    }
}
