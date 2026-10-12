package app.kinsight.feature.devicedetail

import app.kinsight.core.data.AppList
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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
class AllAppsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)
    private val range = DetailRange.of(Period.WEEK, LocalDate.of(2026, 10, 10), WeekFields.of(DayOfWeek.MONDAY, 4))
    private val list = AppList(kid, range, emptyList(), LocalDate.of(2026, 10, 10))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRotationReusesTheLoadedListWithoutReloading() = runTest(dispatcher) {
        var loads = 0
        val source = MutableStateFlow<AppList?>(list).onStart { loads++ }
        val vm = AllAppsViewModel(source)
        val first = backgroundScope.launch { vm.list.collect {} }
        assertEquals(list, vm.list.value)
        // Rotation: the screen's collector goes away and a new one attaches at once to the same view model.
        first.cancel()
        val second = backgroundScope.launch { vm.list.collect {} }
        assertEquals(list, vm.list.value)
        assertEquals(1, loads)
        second.cancel()
    }
}
