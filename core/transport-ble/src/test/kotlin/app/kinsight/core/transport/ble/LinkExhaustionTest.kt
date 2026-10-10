package app.kinsight.core.transport.ble

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ten dials in a row that fail with «No resources Available» mean our own radio is stuck: restart it (finding 2). */
class LinkExhaustionTest {
    private val noResources = IOException("android.bluetooth.BluetoothSocketException: No resources Available")

    @Test
    fun theTenthNoResourcesFailureInARowAsksForARestartOnce() {
        val w = LinkExhaustion()
        repeat(9) { assertFalse(w.onFailure(noResources)) }
        assertTrue(w.onFailure(noResources))
        // Counted from zero again: the restart gets its own ten tries.
        repeat(9) { assertFalse(w.onFailure(noResources)) }
        assertTrue(w.onFailure(noResources))
    }

    @Test
    fun aSuccessOrAnotherFailureBreaksTheRun() {
        val w = LinkExhaustion()
        repeat(9) { w.onFailure(noResources) }
        w.onSuccess()
        repeat(9) { assertFalse(w.onFailure(noResources)) }
        assertFalse(w.onFailure(IOException("GATT timed out")))
        repeat(9) { assertFalse(w.onFailure(noResources)) }
        assertTrue(w.onFailure(noResources))
    }

    @Test
    fun theMessageIsFoundInTheCauseChainToo() {
        val w = LinkExhaustion(threshold = 1)
        assertTrue(w.onFailure(IOException("connect failed", RuntimeException("No resources Available"))))
    }

    @Test
    fun thresholdIsTen() = assertEquals(10, LinkExhaustion.THRESHOLD)
}
