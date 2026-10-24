package app.kinsight.core.designsystem

import app.kinsight.core.designsystem.component.LimitState
import app.kinsight.core.designsystem.component.limitProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class ComponentMathTest {
    private val min = 60_000L

    @Test
    fun underTheLimitTheBarSaysWhatIsLeft() {
        val p = limitProgress(90 * min, 120 * min)
        assertEquals(0.75f, p.fraction, 0.0001f)
        assertEquals(30 * min, p.leftMs)
        assertEquals(0L, p.overMs)
        assertEquals(LimitState.UNDER, p.state)
    }

    /** Review Focus 1: «Лимит исчерпан», never «<1 мин» either way. */
    @Test
    fun atTheLimitOrUnderAMinuteOverItIsReached() {
        assertEquals(LimitState.REACHED, limitProgress(120 * min, 120 * min).state)
        val justOver = limitProgress(120 * min + 59_999, 120 * min)
        assertEquals(LimitState.REACHED, justOver.state)
        assertEquals(1f, justOver.fraction)
    }

    @Test
    fun pastTheLimitByAMinuteOrMoreItIsOver() {
        val p = limitProgress(150 * min, 120 * min)
        assertEquals(LimitState.OVER, p.state)
        assertEquals(30 * min, p.overMs)
        assertEquals(1f, p.fraction)
    }

    @Test
    fun negativeUsageAndAZeroLimitDoNotBreakTheBar() {
        assertEquals(0f, limitProgress(-5, 120 * min).fraction)
        assertEquals(LimitState.REACHED, limitProgress(0, 0).state)
    }
}
