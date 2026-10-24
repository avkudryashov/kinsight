package app.kinsight.core.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.BlockReason
import app.kinsight.core.data.SystemAllowList
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.data.TodayUsage
import app.kinsight.core.model.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EnforcerTest {
    private val child = DeviceId("00112233445566778899aabbccddeeff")
    private var now = 1_000_000L

    private fun enforcer(
        rules: List<ThresholdRule>,
        today: () -> TodayUsage? = { null },
        enabled: () -> Boolean = { true },
        system: SystemAllowList = SystemAllowList { false },
    ) = Enforcer(
        rules = { rules }, categoryOf = { AppCategory.GAMES }, today = today, pinAllowed = { emptySet() },
        system = system, enabled = enabled, clock = { now },
    )

    private fun usage(appMs: Long) = TodayUsage(appMs, mapOf(AppCategory.GAMES to appMs), mapOf(GAME to appMs))

    @Test
    fun aBlockedPackageGetsOneBlockActionPerSecond() {
        val e = enforcer(listOf(ThresholdRule.block(child, ThresholdTarget.App(GAME))!!))
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.Blocked), e.onForeground(GAME))
        now += Enforcer.DEBOUNCE_MS - 1
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        now += 1
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.Blocked), e.onForeground(GAME))
    }

    @Test
    fun tickBlocksWhenTheLimitRunsOut() {
        val limit = ThresholdRule.of(child, ThresholdTarget.App(GAME), HOUR, blockOnLimit = true)!!
        var today = usage(HOUR - 1)
        val e = enforcer(listOf(limit), today = { today })
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        assertEquals(Enforcer.Action.None, e.onTick())
        today = usage(HOUR)
        now += Enforcer.TICK_MS
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.LimitPassed(ThresholdTarget.App(GAME), HOUR)), e.onTick())
        // The app is closed: the next tick has nothing to re-check until a new window comes up.
        now += Enforcer.TICK_MS
        assertEquals(Enforcer.Action.None, e.onTick())
    }

    @Test
    fun disabledNeverBlocks() {
        val e = enforcer(listOf(ThresholdRule.block(child, ThresholdTarget.App(GAME))!!), enabled = { false })
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        assertEquals(Enforcer.Action.None, e.onTick())
    }

    @Test
    fun kinsightItselfIsNeverBlocked() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val own = app.packageName
        val e = enforcer(
            listOf(ThresholdRule.block(child, ThresholdTarget.App(own))!!, ThresholdRule.block(child, ThresholdTarget.Category(AppCategory.GAMES))!!),
            system = AndroidSystemAllowList(app, defaults = { emptySet() }),
        )
        assertEquals(Enforcer.Action.None, e.onForeground(own))
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.CategoryBlocked(AppCategory.GAMES)), e.onForeground(GAME))
    }

    private companion object {
        const val GAME = "com.example.game"
        const val HOUR = 3_600_000L
    }
}
