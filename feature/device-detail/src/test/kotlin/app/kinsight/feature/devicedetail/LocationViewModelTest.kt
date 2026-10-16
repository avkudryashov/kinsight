package app.kinsight.feature.devicedetail

import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)

    private class FakeSource(val ready: MutableStateFlow<Boolean>) : LocationSource {
        val views = MutableSharedFlow<ChildLocation?>()
        var collections = 0
        override fun child(): Flow<ChildLocation?> = views.onStart { collections++ }
        override fun groupReady(): Flow<Boolean> = ready
    }

    @Test
    fun oneCollectionGivesTheChildAndLoadedTogether() = runTest(dispatcher) {
        val source = FakeSource(MutableStateFlow(true))
        val vm = LocationViewModel(source)
        val job = launch { vm.state.collect {} }
        assertNull(vm.state.value.child)
        assertFalse(vm.state.value.loaded, "nothing arrived yet")
        val view = ChildLocation(kid, null, null, emptyList(), emptyList(), emptyList())
        source.views.emit(view)
        assertEquals(LocationState(view, loaded = true), vm.state.value)
        assertEquals(1, source.collections)
        job.cancel()
    }

    @Test
    fun aNullViewIsMissingOnlyOnceTheGroupIsReadable() = runTest(dispatcher) {
        val ready = MutableStateFlow(false)
        val source = FakeSource(ready)
        val vm = LocationViewModel(source)
        val job = launch { vm.state.collect {} }
        source.views.emit(null)
        assertFalse(vm.state.value.loaded, "a locked group is still loading")
        ready.value = true
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.child)
        job.cancel()
    }
}
