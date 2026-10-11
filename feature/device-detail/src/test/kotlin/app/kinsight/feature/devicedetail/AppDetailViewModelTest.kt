package app.kinsight.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModelTest {
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val sat = LocalDate.of(2026, 10, 10)
    private val sunFirst = WeekFields.of(DayOfWeek.SUNDAY, 1)
    private val dispatcher = UnconfinedTestDispatcher()
    private val ticks = MutableSharedFlow<Unit>()
    private val pkg = "com.example.game"

    /** Summaries for the last two weeks; the category is the catalog's GAMES unless the parent overrode it. */
    private inner class FakeSource : AppSource {
        val overrides = MutableStateFlow<Map<String, AppCategory>>(emptyMap())
        val known = (0L..13).map { sat.minusDays(it) }.toSet()

        var viewerToday = sat
        var canChange = true
        var active = true
        val limit = MutableStateFlow<ThresholdRule?>(null)

        override fun threshold(): Flow<ThresholdRule?> = limit

        val changedBy = MutableStateFlow<String?>(null)

        override fun categoryChangedBy(): Flow<String?> = changedBy

        override suspend fun setThreshold(rule: ThresholdRule) {
            limit.value = rule
        }

        override suspend fun removeThreshold(id: String) {
            if (limit.value?.id == id) limit.value = null
        }

        override fun today(): LocalDate = viewerToday

        override fun app(range: DetailRange): Flow<AppDetail?> = overrides.map { o ->
            val days = range.dates().map { d -> (d in known).let { DayTotal(d, if (it) 60_000 else 0, 0, hasData = it, known = it) } }
            AppDetail(
                kid, pkg, range, "Game", null, o[pkg] ?: AppCategory.GAMES, o.containsKey(pkg), days, days.sumOf { it.totalMs },
                hours = null, rank = 1, canChangeCategory = canChange,
                canGoBack = known.any { it.isBefore(range.start) }, canGoForward = range.end.isBefore(viewerToday), today = viewerToday, defaultCategory = AppCategory.GAMES, active = active,
            )
        }

        var failWrites = false
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun setCategory(category: AppCategory?) {
            beforeWrite()
            if (failWrites) throw IllegalStateException("disk full")
            overrides.value = if (category == null) overrides.value - pkg else overrides.value + (pkg to category)
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(source: FakeSource, initial: DetailRange, saved: SavedStateHandle = SavedStateHandle()): AppDetailViewModel {
        val vm = AppDetailViewModel(source, saved, sunFirst, initial, ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        return vm
    }

    private fun week(anchor: LocalDate) = DetailRange.of(Period.WEEK, anchor, sunFirst)

    /** The category write runs on the app's scope (like limit writes): leaving the screen at once does not cancel it. */
    @Test
    fun aCategoryWriteOutlivesTheScreen() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            beforeWrite = {
                started.complete(Unit)
                gate.await()
            }
        }
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider.create(
                store,
                viewModelFactory { initializer { AppDetailViewModel(source, SavedStateHandle(), sunFirst, week(sat), ticks = ticks, writeScope = app) } },
            )[AppDetailViewModel::class]
            val collector = app.launch { vm.detail.collect {} }
            withTimeout(5_000) { while (vm.detail.value?.canChangeCategory != true) delay(5) }
            vm.setCategory(AppCategory.SOCIAL)
            withTimeout(5_000) { started.await() }
            collector.cancel()
            store.clear() // the screen is gone while the write is in flight
            gate.complete(Unit)
            withTimeout(5_000) { source.overrides.first { it == mapOf(pkg to AppCategory.SOCIAL) } }
        } finally {
            app.coroutineContext.job.cancelAndJoin()
            store.clear()
        }
    }

    @Test
    fun opensOnTheGivenRangeAndNavigatesLikeTheDetail() = runTest(dispatcher) {
        val vm = vm(FakeSource(), week(sat.minusDays(7)))
        assertEquals(week(sat.minusDays(7)), vm.range.value)
        vm.forward()
        assertEquals(week(sat), vm.range.value)
        vm.forward() // not past today
        assertEquals(week(sat), vm.range.value)
        vm.back()
        assertEquals(week(sat.minusDays(7)), vm.range.value) // Sun 27 Sep, the oldest summary
        assertFalse(vm.detail.value!!.canGoBack)
        vm.back() // no older summary
        assertEquals(week(sat.minusDays(7)), vm.range.value)
        vm.openDay(sat.minusDays(10))
        assertEquals(DetailRange(Period.DAY, sat.minusDays(10), sat.minusDays(10)), vm.range.value)
        vm.select(Period.MONTH)
        assertEquals(DetailRange.of(Period.MONTH, sat.minusDays(10), sunFirst), vm.range.value) // September
    }

    @Test
    fun aRestoredRangeWinsOverTheNavigationArgument() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(FakeSource(), week(sat), saved)
        first.openDay(sat.minusDays(3))
        val again = vm(FakeSource(), week(sat), saved) // process death: the route passes its argument again
        assertEquals(DetailRange(Period.DAY, sat.minusDays(3), sat.minusDays(3)), again.range.value)
    }

    @Test
    fun aRangeContainingTodayFollowsItAcrossAWeekBoundary() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source, week(sat))
        source.viewerToday = sat.plusDays(1) // Sunday: a new Sunday-first week
        ticks.emit(Unit)
        assertEquals(week(sat.plusDays(1)), vm.range.value)
    }

    @Test
    fun aPastRangeStaysWhenTheDayChanges() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source, week(sat.minusDays(7)))
        source.viewerToday = sat.plusDays(1)
        ticks.emit(Unit)
        assertEquals(week(sat.minusDays(7)), vm.range.value)
    }

    @Test
    fun aCategoryChangeReEmitsTheApp() = runTest(dispatcher) {
        val vm = vm(FakeSource(), week(sat))
        assertEquals(AppCategory.GAMES, vm.detail.value!!.category)
        vm.setCategory(AppCategory.SOCIAL)
        assertEquals(AppCategory.SOCIAL, vm.detail.value!!.category)
        assertTrue(vm.detail.value!!.overridden)
        vm.setCategory(null) // «По умолчанию»
        assertEquals(AppCategory.GAMES, vm.detail.value!!.category)
        assertFalse(vm.detail.value!!.overridden)
    }

    @Test
    fun theCategoryAuthorNameFollowsTheSource() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source, week(sat))
        backgroundScope.launch { vm.categoryChangedBy.collect {} }
        assertNull(vm.categoryChangedBy.value)
        source.changedBy.value = "Dad"
        assertEquals("Dad", vm.categoryChangedBy.value)
        source.changedBy.value = null
        assertNull(vm.categoryChangedBy.value)
    }

    @Test
    fun aFailedCategoryWriteIsLoggedNotThrown() = runTest(dispatcher) {
        val source = FakeSource().apply { failWrites = true }
        val vm = vm(source, week(sat))
        vm.setCategory(AppCategory.SOCIAL) // an uncaught throw here would fail the test (and crash the app)
        assertEquals(AppCategory.GAMES, vm.detail.value!!.category)
        source.failWrites = false
        vm.setCategory(AppCategory.SOCIAL) // the screen still works
        assertEquals(AppCategory.SOCIAL, vm.detail.value!!.category)
    }

    @Test
    fun aParentSetsChangesAndRemovesTheAppsLimit() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source, week(sat))
        backgroundScope.launch { vm.threshold.collect {} }
        val hour = 3_600_000L
        vm.setThreshold(hour)
        assertEquals(ThresholdRule.of(kid.id, ThresholdTarget.App(pkg), hour), vm.threshold.value)
        vm.setThreshold(2 * hour)
        assertEquals(2 * hour, vm.threshold.value?.limitMs)
        vm.setThreshold(10 * 60_000L) // off the 15-minute step
        assertEquals(2 * hour, vm.threshold.value?.limitMs)
        vm.removeThreshold()
        assertNull(vm.threshold.value)
    }

    /** Final fix item 6: a child that left or was removed gets no new limit (and «Notify if more than…» is hidden). */
    @Test
    fun noAppLimitForAChildThatIsNoLongerActive() = runTest(dispatcher) {
        val source = FakeSource().apply { active = false }
        val vm = vm(source, week(sat))
        assertFalse(canSetThreshold(vm.detail.value!!))
        vm.setThreshold(3_600_000L)
        assertNull(source.limit.value)
    }

    @Test
    fun noAppLimitWhereTheCategoryCannotChange() = runTest(dispatcher) {
        val source = FakeSource().apply { canChange = false } // a child's own phone
        val vm = vm(source, week(sat))
        vm.setThreshold(3_600_000L)
        assertNull(source.limit.value)
    }

    private fun appSource(app: Flow<AppDetail?>, ready: Boolean = true) = object : AppSource {
        override fun today(): LocalDate = sat

        override fun groupReady(): Flow<Boolean> = flowOf(ready)

        override fun app(range: DetailRange): Flow<AppDetail?> = app

        override suspend fun setCategory(category: AppCategory?) {}
    }

    @Test
    fun anUnknownDeviceIsMissingOnTheAppScreen() = runTest(dispatcher) {
        val vm = AppDetailViewModel(appSource(flowOf(null)), SavedStateHandle(), sunFirst, week(sat), ticks = ticks)
        backgroundScope.launch { vm.detail.collect {} }
        assertNull(vm.detail.value)
        assertTrue(vm.loaded.value)
    }

    @Test
    fun theAppScreenIsLoadingBeforeTheFirstViewAndOnAnUnreadableGroup() = runTest(dispatcher) {
        val waiting = AppDetailViewModel(appSource(MutableSharedFlow()), SavedStateHandle(), sunFirst, week(sat), ticks = ticks)
        backgroundScope.launch { waiting.detail.collect {} }
        assertFalse(waiting.loaded.value)
        val broken = AppDetailViewModel(appSource(flowOf(null), ready = false), SavedStateHandle(), sunFirst, week(sat), ticks = ticks)
        backgroundScope.launch { broken.detail.collect {} }
        assertFalse(broken.loaded.value)
    }
}
