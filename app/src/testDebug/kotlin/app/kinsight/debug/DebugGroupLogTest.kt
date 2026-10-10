package app.kinsight.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebugGroupLogTest {
    @Test
    fun theLogLineNeverCarriesTheResultData() {
        val seed = "c2VlZC13aXRoLXRoZS1kaXNjb3Zlcnkta2V5"
        val line = DebugGroupReceiver.logLine("app.kinsight.debug.CREATE_GROUP", 0, seed)
        assertFalse(seed in line)
        assertEquals("app.kinsight.debug.CREATE_GROUP: code=0, ${seed.length} chars", line)
    }

    @Test
    fun errorsAreLoggedWithoutTheirMessage() {
        val line = DebugGroupReceiver.logLine("app.kinsight.debug.JOIN_GROUP", 1, "error: IllegalStateException: seed abc")
        assertFalse("seed abc" in line)
    }
}
