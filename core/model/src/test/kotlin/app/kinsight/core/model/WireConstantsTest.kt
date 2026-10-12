package app.kinsight.core.model

import app.kinsight.core.model.proto.StateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WireConstantsTest {
    @Test
    fun theRemainderCanNeverBeAPackageName() {
        // Android package names allow letters, digits, '_' and '.' only.
        assertEquals("~other", WireConstants.REMAINDER_PACKAGE)
        assertFalse(Regex("[A-Za-z0-9_.]+").matches(WireConstants.REMAINDER_PACKAGE))
    }

    @Test
    fun sharingKindsArePauseAndResume() {
        assertEquals(setOf(StateEvent.Kind.SHARING_PAUSED, StateEvent.Kind.SHARING_RESUMED), WireConstants.SHARING_KINDS)
    }
}
