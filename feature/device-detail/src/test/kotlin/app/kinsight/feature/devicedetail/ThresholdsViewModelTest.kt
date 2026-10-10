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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
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

        var writes = 0

        override suspend fun set(rule: ThresholdRule) {
            writes++
            rules.value = rules.value.filter { it.id != rule.id } + rule
        }

        override suspend fun remove(id: String) {
            writes++
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
        assertEquals(0, source.writes)
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
}
