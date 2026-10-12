package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val sat = LocalDate.of(2026, 10, 10)
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)
    private val dispatcher = UnconfinedTestDispatcher()

    /** Summaries exist for [known] dates; the child's today is [childToday] (null: the viewer's). */
    private inner class FakeSource(known: Set<LocalDate>) : DetailSource {
        val viewerToday = MutableStateFlow(sat)
        val known = MutableStateFlow(known)
        var childToday: LocalDate? = null
        val parent = MutableStateFlow(false)
        val rules = MutableStateFlow<List<ThresholdRule>>(emptyList())

        override fun viewerIsParent(): Flow<Boolean> = parent

        override fun thresholds(): Flow<List<ThresholdRule>> = rules

        override fun today(): LocalDate = viewerToday.value

        override fun detail(range: DetailRange): Flow<DeviceDetail?> = known.map { k ->
            val today = maxOf(viewerToday.value, childToday ?: viewerToday.value)
            val days = range.dates().map { d -> (d in k && !d.isAfter(today)).let { DayTotal(d, if (it) 60_000 else 0, 0, hasData = it, known = it) } }
            DeviceDetail(
                kid, range, days, days.sumOf { it.totalMs }, 0, emptyList(), emptyList(), Sharing.On,
                canGoBack = k.any { it.isBefore(range.start) }, canGoForward = range.end.isBefore(today), today = today,
            )
        }
    }

    private val lastTwoWeeks = (0L..13).map { sat.minusDays(it) }.toSet()
    private val ticks = MutableSharedFlow<Unit>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(source: FakeSource, saved: SavedStateHandle = SavedStateHandle()): DeviceDetailViewModel {
        val vm = DeviceDetailViewModel(source, saved, sunFirst, ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        return vm
    }

    private fun week(anchor: LocalDate) = DetailRange.of(Period.WEEK, anchor, sunFirst)

    @Test
    fun startsOnTheWeekContainingTodayAndStepsWithinBounds() = runTest(dispatcher) {
        val vm = vm(FakeSource(lastTwoWeeks))
        assertEquals(DetailRange(Period.WEEK, sat.minusDays(6), sat), vm.range.value)
        vm.forward() // today's week is the last one
        assertEquals(week(sat), vm.range.value)
        vm.back()
        assertEquals(week(sat.minusDays(7)), vm.range.value)
        vm.back() // nothing older than 27 Sep
        assertEquals(week(sat.minusDays(7)), vm.range.value)
        vm.forward()
        assertEquals(week(sat), vm.range.value)
        assertEquals(sat, vm.detail.value?.range?.end)
    }

    @Test
    fun switchingToDayGoesToTheLastKnownDayAndKeepsTheAnchor() = runTest(dispatcher) {
        val source = FakeSource(lastTwoWeeks - sat) // today has not arrived yet
        val vm = vm(source)
        vm.select(Period.DAY)
        assertEquals(DetailRange(Period.DAY, sat.minusDays(1), sat.minusDays(1)), vm.range.value)
        vm.back()
        vm.back()
        vm.select(Period.WEEK) // Wed 7 Oct stays in view
        assertEquals(week(sat.minusDays(3)), vm.range.value)
        vm.back()
        vm.select(Period.DAY) // last known day of 27 Sep..3 Oct
        assertEquals(LocalDate.of(2026, 10, 3), vm.range.value.start)
        vm.select(Period.MONTH)
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), vm.range.value)
    }

    @Test
    fun openDayOpensThatDayButNotTheFuture() = runTest(dispatcher) {
        val vm = vm(FakeSource(lastTwoWeeks))
        vm.select(Period.MONTH)
        vm.openDay(LocalDate.of(2026, 10, 6))
        assertEquals(DetailRange(Period.DAY, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)), vm.range.value)
        vm.select(Period.MONTH)
        vm.openDay(sat.plusDays(3))
        assertEquals(Period.MONTH, vm.range.value.period)
    }

    @Test
    fun restoresPeriodAndAnchorAfterProcessDeath() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(FakeSource(lastTwoWeeks), saved)
        first.back()
        first.select(Period.DAY)
        val restored = vm(FakeSource(lastTwoWeeks), SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertEquals(first.range.value, restored.range.value)
        assertEquals(Period.DAY, restored.range.value.period)
        val fromIso = vm(
            FakeSource(lastTwoWeeks),
            SavedStateHandle(mapOf(DeviceDetailViewModel.KEY_PERIOD to "MONTH", DeviceDetailViewModel.KEY_ANCHOR to "2026-09-15")),
        )
        assertEquals(DetailRange(Period.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), fromIso.range.value)
    }

    @Test
    fun movesToTheNewTodayAfterMidnightUnlessTheUserNavigated() = runTest(dispatcher) {
        val source = FakeSource(lastTwoWeeks)
        val vm = vm(source)
        source.viewerToday.value = sat.plusDays(1) // Sunday starts a new week
        ticks.emit(Unit)
        assertEquals(week(sat.plusDays(1)), vm.range.value)

        val other = FakeSource(lastTwoWeeks)
        val navigated = vm(other)
        navigated.back()
        other.viewerToday.value = sat.plusDays(1)
        ticks.emit(Unit)
        assertEquals(week(sat.minusDays(7)), navigated.range.value)
    }

    @Test
    fun aChildAheadOfTheParentOpensOnItsOwnToday() = runTest(dispatcher) {
        val source = FakeSource(lastTwoWeeks + sat.plusDays(1)).apply { childToday = sat.plusDays(1) }
        val vm = vm(source)
        assertEquals(week(sat.plusDays(1)), vm.range.value)
        vm.select(Period.DAY)
        assertEquals(sat.plusDays(1), vm.range.value.start)
    }

    @Test
    fun midnightInsideTheSameWeekRefreshesTheDetail() = runTest(dispatcher) {
        val wed = sat.minusDays(3)
        val source = FakeSource(lastTwoWeeks).apply { viewerToday.value = wed }
        val vm = vm(source)
        assertEquals(wed, vm.detail.value?.today)
        assertEquals(false, vm.detail.value?.days?.first { it.date == wed.plusDays(1) }?.known)
        source.viewerToday.value = wed.plusDays(1)
        ticks.emit(Unit)
        assertEquals(week(wed), vm.range.value)
        assertEquals(wed.plusDays(1), vm.detail.value?.today)
        assertEquals(true, vm.detail.value?.days?.first { it.date == wed.plusDays(1) }?.known)
    }

    @Test
    fun thresholdsAreOnlyListedForAParent() = runTest(dispatcher) {
        val source = FakeSource(lastTwoWeeks)
        val vm = vm(source)
        backgroundScope.launch { vm.thresholds.collect {} }
        val total = ThresholdRule.of(kid.id, ThresholdTarget.Total, 3_600_000L)!!
        source.rules.value = listOf(total)
        assertEquals(null, vm.thresholds.value, "a child's phone: no entry, no line")
        source.parent.value = true
        assertEquals(listOf(total), vm.thresholds.value)
    }

    private fun source(detail: Flow<DeviceDetail?>, ready: Boolean = true) = object : DetailSource {
        override fun today(): LocalDate = sat

        override fun groupReady(): Flow<Boolean> = flowOf(ready)

        override fun detail(range: DetailRange): Flow<DeviceDetail?> = detail
    }

    @Test
    fun anUnknownDeviceIsMissingNotLoading() = runTest(dispatcher) {
        val vm = DeviceDetailViewModel(source(flowOf(null)), SavedStateHandle(), sunFirst, ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        assertEquals(null, vm.detail.value)
        assertEquals(true, vm.loaded.value)
    }

    @Test
    fun beforeTheFirstViewItIsStillLoading() = runTest(dispatcher) {
        val vm = DeviceDetailViewModel(source(MutableSharedFlow()), SavedStateHandle(), sunFirst, ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        assertEquals(false, vm.loaded.value)
    }

    @Test
    fun anUnreadableGroupIsNotAMissingDevice() = runTest(dispatcher) {
        val vm = DeviceDetailViewModel(source(flowOf(null), ready = false), SavedStateHandle(), sunFirst, ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        assertEquals(false, vm.loaded.value, "a storage or key error never reads as «no longer in the group»")
    }
}
