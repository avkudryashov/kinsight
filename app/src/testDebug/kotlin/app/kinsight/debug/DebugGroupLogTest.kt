package app.kinsight.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebugGroupLogTest {
    @Test
    fun theLogLineNeverCarriesTheResultData() {
        val invite = "kinsight://pair/1/c2VlZC13aXRoLXRoZS1kaXNjb3Zlcnkta2V5"
        val line = DebugGroupReceiver.logLine("app.kinsight.debug.INVITE_URI", 0, invite)
        assertFalse(invite in line)
        assertEquals("app.kinsight.debug.INVITE_URI: code=0, ${invite.length} chars", line)
    }

    @Test
    fun errorsAreLoggedWithoutTheirMessage() {
        val line = DebugGroupReceiver.logLine("app.kinsight.debug.STATUS", 1, "error: IllegalStateException: token abc")
        assertFalse("token abc" in line)
    }
}
