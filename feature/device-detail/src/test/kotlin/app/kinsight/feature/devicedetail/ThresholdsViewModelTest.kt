package app.kinsight.feature.devicedetail

import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.Categories
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.model.DeviceId
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThresholdsViewModelTest {
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val other = DeviceId("dd".repeat(16))
    private val sat = LocalDate.of(2026, 10, 10)
    private val dispatcher = UnconfinedTestDispatcher()
    private val hour = 3_600_000L

    private inner class FakeSource(parent: Boolean = true) : ThresholdSource {
        val rules = MutableStateFlow<List<ThresholdRule>>(emptyList())
        val parent = MutableStateFlow(parent)
        val list = MutableStateFlow<AppList?>(
            AppList(
                kid, DetailRange(Period.MONTH, sat.minusDays(29), sat),
                listOf(
                    AppUsage("com.example.video", "Video", null, 5 * hour),
                    AppUsage(Categories.REMAINDER_PACKAGE, "", null, hour),
                    AppUsage("com.example.game", "Game", null, 2 * hour),
                ),
                sat,
            ),
        )

        override fun rules(): Flow<List<ThresholdRule>> = rules

        override fun viewerIsParent(): Flow<Boolean> = parent

        override fun apps(): Flow<AppList?> = list

        val today = MutableStateFlow<Long?>(null)

        override fun todayMs(): Flow<Long?> = today

        val writes = java.util.concurrent.atomic.AtomicInteger()

        /** A slow storage remove (blocking), to show the order of writes. */
        @Volatile var removeDelayMs = 0L

        override suspend fun set(rule: ThresholdRule) {
            writes.incrementAndGet()
            rules.value = rules.value.filter { it.id != rule.id } + rule
        }

        override suspend fun remove(id: String) {
            if (removeDelayMs > 0) Thread.sleep(removeDelayMs)
            writes.incrementAndGet()
            rules.value = rules.value.filter { it.id != id }
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(source: FakeSource): ThresholdsViewModel {
        val vm = ThresholdsViewModel(source, kid.id, backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private fun rule(target: ThresholdTarget, ms: Long, child: DeviceId = kid.id) = ThresholdRule.of(child, target, ms)!!

    @Test
    fun todaysTotalComesWithTheState() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source)
        assertEquals(null, vm.state.value?.todayMs)
        source.today.value = 2 * hour
        assertEquals(2 * hour, vm.state.value?.todayMs)
    }

    @Test
    fun listsOnlyThisChildsRulesTotalThenCategoriesThenAppsAndTheAppsToPick() = runTest(dispatcher) {
        val source = FakeSource()
        source.rules.value = listOf(
            rule(ThresholdTarget.App("com.example.video"), hour),
            rule(ThresholdTarget.Category(AppCategory.VIDEO), hour),
            rule(ThresholdTarget.Total, 3 * hour, child = other),
            rule(ThresholdTarget.App("com.example.game"), hour),
            rule(ThresholdTarget.Category(AppCategory.GAMES), hour),
            rule(ThresholdTarget.Total, 3 * hour),
        )
        val state = assertNotNull(vm(source).state.value)
        assertEquals("Kid", state.childName)
        assertTrue(state.canEdit)
        assertEquals(
            listOf(
                ThresholdTarget.Total,
                ThresholdTarget.Category(AppCategory.GAMES),
                ThresholdTarget.Category(AppCategory.VIDEO),
                ThresholdTarget.App("com.example.game"),
                ThresholdTarget.App("com.example.video"),
            ),
            state.rules.map { it.target },
        )
        assertTrue(state.rules.all { it.child == kid.id })
        // `~other` is not an app a rule can name.
        assertEquals(listOf("com.example.video", "com.example.game"), state.apps.map { it.packageName })
    }

    @Test
    fun savingAddsARuleAndSavingTheSameTargetEditsIt() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source)
        assertTrue(vm.save(ThresholdTarget.Category(AppCategory.GAMES), hour))
        assertEquals(listOf(rule(ThresholdTarget.Category(AppCategory.GAMES), hour)), vm.state.value!!.rules)
        assertTrue(vm.save(ThresholdTarget.Category(AppCategory.GAMES), 2 * hour))
        assertEquals(listOf(rule(ThresholdTarget.Category(AppCategory.GAMES), 2 * hour)), vm.state.value!!.rules)
        assertTrue(vm.save(ThresholdTarget.App("com.example.game"), 15 * 60_000L))
        assertEquals(2, vm.state.value!!.rules.size)
    }

    @Test
    fun savingRefusesALimitOffTheStepOrOutOfRangeAndTheRemainder() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = vm(source)
        assertFalse(vm.save(ThresholdTarget.Total, 10 * 60_000L))
        assertFalse(vm.save(ThresholdTarget.Total, 12 * hour + 15 * 60_000L))
        assertFalse(vm.save(ThresholdTarget.Total, 0))
        assertFalse(vm.save(ThresholdTarget.App(Categories.REMAINDER_PACKAGE), hour))
        assertTrue(vm.save(ThresholdTarget.Total, 12 * hour))
        assertEquals(listOf(rule(ThresholdTarget.Total, 12 * hour)), source.rules.value)
    }

    @Test
    fun aDeleteIsOnlyHiddenUntilTheUndoWindowEndsSoUndoNeverTouchesStorage() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val vm = vm(source)
        vm.delete(r)
        assertEquals(emptyList(), vm.state.value!!.rules, "hidden at once")
        assertEquals(listOf(r), source.rules.value, "not removed yet: an alerts run would drop its markers")
        vm.undo(r)
        assertEquals(listOf(r), vm.state.value!!.rules)
        assertEquals(listOf(r), source.rules.value)
        assertEquals(0, source.writes.get())
    }

    @Test
    fun aDeleteIsCommittedWhenTheSnackbarGoesWithoutUndo() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val vm = vm(source)
        vm.delete(r)
        vm.commitDelete(r)
        assertEquals(emptyList(), source.rules.value)
        assertEquals(emptyList(), vm.state.value!!.rules)
    }

    @Test
    fun savingTheSameTargetInsideTheUndoWindowCancelsThePendingDelete() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val vm = vm(source)
        vm.delete(r)
        assertTrue(vm.save(ThresholdTarget.Total, 2 * hour))
        vm.commitDelete(r) // the snackbar times out afterwards
        val again = rule(ThresholdTarget.Total, 2 * hour)
        assertEquals(listOf(again), source.rules.value)
        assertEquals(listOf(again), vm.state.value!!.rules, "shown again")
    }

    @Test
    fun leavingTheScreenCommitsAPendingDelete() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        val keep = rule(ThresholdTarget.Category(AppCategory.GAMES), hour)
        source.rules.value = listOf(r, keep)
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ThresholdsViewModel(source, kid.id, backgroundScope) } })[ThresholdsViewModel::class]
        backgroundScope.launch { vm.state.collect {} }
        vm.delete(r)
        store.clear() // the screen was left before the snackbar was dismissed
        assertEquals(listOf(keep), source.rules.value)
    }

    /** A process death inside the «Undo» window: the handle's copy is what the recreated view model gets. */
    private fun recreated(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

    @Test
    fun aPendingDeleteSurvivesRecreationAndIsOfferedAgain() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val saved = SavedStateHandle()
        val first = ThresholdsViewModel(source, kid.id, backgroundScope, saved, clock = { 1_000L })
        backgroundScope.launch { first.state.collect {} }
        first.delete(r)

        val again = ThresholdsViewModel(source, kid.id, backgroundScope, recreated(saved), clock = { 5_000L })
        backgroundScope.launch { again.state.collect {} }
        assertEquals(emptyList(), again.state.value!!.rules, "still hidden")
        assertEquals(setOf(r.id), again.pendingDeletes.value, "its snackbar is shown again")
        assertEquals(listOf(r), source.rules.value)
        again.undo(r.id)
        assertEquals(listOf(r), again.state.value!!.rules)
        assertEquals(emptySet(), again.pendingDeletes.value)
    }

    @Test
    fun aPendingDeleteWhoseWindowPassedIsCommittedOnRecreation() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val saved = SavedStateHandle()
        val first = ThresholdsViewModel(source, kid.id, backgroundScope, saved, clock = { 1_000L })
        backgroundScope.launch { first.state.collect {} }
        first.delete(r)

        val late = 1_000L + ThresholdsViewModel.UNDO_WINDOW_MS + 1
        val again = ThresholdsViewModel(source, kid.id, backgroundScope, recreated(saved), clock = { late })
        backgroundScope.launch { again.state.collect {} }
        assertEquals(emptyList(), source.rules.value)
        assertEquals(emptySet(), again.pendingDeletes.value, "no snackbar for a window that passed")
    }

    /** Re-review item 4: the window of a queued delete starts when its snackbar appears, not at the tap. */
    @Test
    fun theUndoWindowStartsWhenTheSnackbarIsShown() = runTest(dispatcher) {
        val source = FakeSource()
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val saved = SavedStateHandle()
        var now = 1_000L
        val first = ThresholdsViewModel(source, kid.id, backgroundScope, saved, clock = { now })
        backgroundScope.launch { first.state.collect {} }
        first.delete(r)
        now = 8_000L // another delete's snackbar was up until now
        first.offered(r.id)

        val again = ThresholdsViewModel(source, kid.id, backgroundScope, recreated(saved), clock = { 12_000L })
        backgroundScope.launch { again.state.collect {} }
        assertEquals(setOf(r.id), again.pendingDeletes.value, "8 s + 10 s: still open at 12 s")
        assertEquals(listOf(r), source.rules.value)
    }

    /** Commits and saves run on one serial lane: a re-added rule is never removed by the older delete's late write. */
    @Test
    fun aSaveAfterACommittedDeleteIsWrittenAfterIt() = runBlocking<Unit> {
        val source = FakeSource().apply { removeDelayMs = 200 }
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider.create(
                store,
                viewModelFactory { initializer { ThresholdsViewModel(source, kid.id, app, writes = ThresholdWrites.lane) } },
            )[ThresholdsViewModel::class]
            app.launch { vm.state.collect {} }
            withTimeout(5_000) { while (vm.state.value?.canEdit != true) delay(5) }
            vm.delete(r)
            vm.commitDelete(r)
            assertTrue(vm.save(ThresholdTarget.Total, 2 * hour))
            withTimeout(5_000) { while (source.writes.get() < 2) delay(5) }
            assertEquals(listOf(rule(ThresholdTarget.Total, 2 * hour)), source.rules.value)
        } finally {
            app.coroutineContext.job.cancelAndJoin()
            store.clear() // the view model's own scope ends before Main is reset
        }
    }

    @Test
    fun aChildsPhoneCannotChangeRules() = runTest(dispatcher) {
        val source = FakeSource(parent = false)
        val r = rule(ThresholdTarget.Total, 3 * hour)
        source.rules.value = listOf(r)
        val vm = vm(source)
        assertFalse(vm.state.value!!.canEdit)
        assertFalse(vm.save(ThresholdTarget.Total, hour))
        vm.delete(r)
        vm.commitDelete(r)
        vm.undo(rule(ThresholdTarget.Category(AppCategory.GAMES), hour))
        assertEquals(listOf(r), source.rules.value)
        assertEquals(listOf(r), vm.state.value!!.rules)
    }

    @Test
    fun theValuePickerStepsBy15MinutesWithin15MinutesAnd12Hours() {
        val q = 15 * 60_000L
        assertEquals(hour, ThresholdValues.DEFAULT_MS)
        assertEquals(2 * q, ThresholdValues.up(q))
        assertEquals(q, ThresholdValues.down(2 * q))
        assertEquals(q, ThresholdValues.down(q))
        assertEquals(12 * hour, ThresholdValues.up(12 * hour))
        assertFalse(ThresholdValues.canDown(q))
        assertTrue(ThresholdValues.canDown(2 * q))
        assertFalse(ThresholdValues.canUp(12 * hour))
        assertTrue(ThresholdValues.canUp(12 * hour - q))
        // Off-step or out-of-range values snap into the picker's grid.
        assertEquals(q, ThresholdValues.clamp(0))
        assertEquals(12 * hour, ThresholdValues.clamp(20 * hour))
        assertEquals(hour, ThresholdValues.clamp(hour + 60_000L))
    }

    @Test
    fun presetsAreOnTheGridAndWithinTheBounds() {
        assertEquals(listOf(30L, 60, 120, 180).map { it * 60_000L }, ThresholdValues.PRESETS_MS)
        ThresholdValues.PRESETS_MS.forEach { assertEquals(it, ThresholdValues.clamp(it)) }
    }
}
