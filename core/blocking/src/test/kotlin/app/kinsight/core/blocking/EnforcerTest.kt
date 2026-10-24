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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EnforcerTest {
    private val child = DeviceId("00112233445566778899aabbccddeeff")
    private var now = 1_000_000L
    private var day = LocalDate.of(2026, 10, 24)

    private fun enforcer(
        rules: List<ThresholdRule>,
        today: () -> DatedUsage? = { null },
        enabled: () -> Boolean = { true },
        system: SystemAllowList = SystemAllowList { it in setOf(OWN, HOME, SYSTEM_UI, KEYBOARD, DIALER) },
        ownPackage: String = OWN,
    ) = Enforcer(
        rules = { rules }, categoryOf = { AppCategory.GAMES }, today = today, date = { day }, pinAllowed = { emptySet() },
        system = system, isHome = { it == HOME }, isOverlay = { it == SYSTEM_UI || it == KEYBOARD }, ownPackage = ownPackage, enabled = enabled, clock = { now },
    )

    private fun limitedGame(): Pair<Enforcer, (Long) -> Unit> {
        var today = usage(HOUR - 1)
        val e = enforcer(listOf(ThresholdRule.of(child, ThresholdTarget.App(GAME), HOUR, blockOnLimit = true)!!), today = { today })
        return e to { ms -> today = usage(ms) }
    }

    private val limitPassed = Enforcer.Action.Block(GAME, BlockReason.LimitPassed(ThresholdTarget.App(GAME), HOUR))

    @Test
    fun kinsightsOwnWindowSuspendsTheAppBehindIt() {
        val (e, setUsage) = limitedGame()
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        assertEquals(Enforcer.Action.None, e.onForeground(OWN))
        setUsage(HOUR)
        assertEquals(Enforcer.Action.None, e.onTick())
        assertEquals(null, e.current)
    }

    @Test
    fun theLauncherClearsTheAppOnScreen() {
        val (e, setUsage) = limitedGame()
        e.onForeground(GAME)
        assertEquals(Enforcer.Action.None, e.onForeground(HOME))
        setUsage(HOUR)
        assertEquals(Enforcer.Action.None, e.onTick())
    }

    @Test
    fun overlayWindowsKeepTheAppUnderThem() {
        val (e, setUsage) = limitedGame()
        e.onForeground(GAME)
        assertEquals(Enforcer.Action.None, e.onForeground(SYSTEM_UI))
        assertEquals(Enforcer.Action.None, e.onForeground(KEYBOARD))
        assertEquals(GAME, e.current)
        setUsage(HOUR)
        assertEquals(limitPassed, e.onTick())
    }

    @Test
    fun aFullAllowListedAppSuspendsTheAppBehindIt() {
        val (e, setUsage) = limitedGame()
        e.onForeground(GAME)
        assertEquals(Enforcer.Action.None, e.onForeground(DIALER))
        assertEquals(null, e.current)
        setUsage(HOUR)
        assertEquals(Enforcer.Action.None, e.onTick())
    }

    @Test
    fun needsUsageOnlyWithABlockingLimit() {
        val block = ThresholdRule.block(child, ThresholdTarget.App(GAME))!!
        val notify = ThresholdRule.of(child, ThresholdTarget.Total, HOUR)!!
        val blocking = ThresholdRule.of(child, ThresholdTarget.App(GAME), HOUR, blockOnLimit = true)!!
        fun needs(enabled: Boolean, vararg rules: ThresholdRule) = EnforcerInputs(enabled, rules.toList()) { AppCategory.OTHER }.needsUsage
        assertEquals(false, needs(true, block, notify))
        assertEquals(true, needs(true, block, blocking))
        assertEquals(false, needs(false, blocking))
    }

    @Test
    fun aLateWindowOfTheClosedAppDoesNotRearmTheTick() {
        val e = enforcer(listOf(ThresholdRule.block(child, ThresholdTarget.App(GAME))!!))
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.Blocked), e.onForeground(GAME))
        now += 100
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        assertEquals(null, e.current)
        now += Enforcer.TICK_MS
        assertEquals(Enforcer.Action.None, e.onTick())
    }

    private fun usage(appMs: Long, totalMs: Long = appMs) = DatedUsage(day, TodayUsage(totalMs, mapOf(AppCategory.GAMES to appMs), mapOf(GAME to appMs)))

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
    fun aDayTotalPassedYesterdayDoesNotBlockTheNextMorning() {
        val total = ThresholdRule.of(child, ThresholdTarget.Total, HOUR, blockOnLimit = true)!!
        var cached: DatedUsage? = usage(HOUR, totalMs = 2 * HOUR)
        val e = enforcer(listOf(total), today = { cached })
        val totalPassed = Enforcer.Action.Block(GAME, BlockReason.LimitPassed(ThresholdTarget.Total, HOUR))
        assertEquals(totalPassed, e.onForeground(GAME))
        assertEquals(null, e.current)
        assertEquals(false, e.usageStale())
        // Midnight passes; nothing re-read the usage since the Block (current is null, the service's old read gate).
        day = day.plusDays(1)
        now += 12 * HOUR
        assertEquals(true, e.usageStale())
        assertEquals(Enforcer.Action.None, e.onForeground(GAME))
        assertEquals(GAME, e.current)
        // The fresh read of the new day is trusted again: under the limit nothing happens, over it the tick blocks.
        cached = usage(10 * 60_000L)
        assertEquals(false, e.usageStale())
        assertEquals(Enforcer.Action.None, e.onTick())
        cached = usage(HOUR)
        assertEquals(totalPassed, e.onTick())
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
            system = AndroidSystemAllowList(app, defaults = { emptySet() }, homes = { emptySet() }, imes = { emptySet() }),
            ownPackage = own,
        )
        assertEquals(Enforcer.Action.None, e.onForeground(own))
        assertEquals(Enforcer.Action.Block(GAME, BlockReason.CategoryBlocked(AppCategory.GAMES)), e.onForeground(GAME))
    }

    private companion object {
        const val GAME = "com.example.game"
        const val HOUR = 3_600_000L
        const val OWN = "app.kinsight"
        const val HOME = "com.example.home"
        const val SYSTEM_UI = "com.android.systemui"
        const val KEYBOARD = "com.example.keyboard"
        const val DIALER = "com.example.dialer"
    }
}
