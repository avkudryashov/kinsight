package app.kinsight.feature.group

import app.kinsight.core.data.PairFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class PairFailureTextTest {
    @Test
    fun everyFailureHasItsOwnMessage() {
        assertEquals(PairFailure.entries.size, PairFailure.entries.map { it.messageRes() }.toSet().size)
    }
}
