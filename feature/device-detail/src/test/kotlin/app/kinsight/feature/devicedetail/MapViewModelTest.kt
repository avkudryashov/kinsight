package app.kinsight.feature.devicedetail

import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val kid = MemberView(DeviceId("cc".repeat(16)), "Kid", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = null)

    private class FakeSource : MapSource {
        val kids = MutableStateFlow<List<ChildLocation>>(emptyList())
        val cloud = MutableStateFlow(false)
        val ready = MutableStateFlow(false)
        override fun children(): Flow<List<ChildLocation>> = kids
        override fun cloudOn(): Flow<Boolean> = cloud
        override fun groupReady(): Flow<Boolean> = ready
    }

    @Test
    fun theStateFollowsTheChildrenTheCloudAndTheGroup() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = MapViewModel(source)
        val job = launch { vm.state.collect {} }
        assertFalse(vm.state.value.loaded, "a locked group is still loading")
        source.ready.value = true
        assertTrue(vm.state.value.loaded)
        val c = ChildLocation(kid, null, null, emptyList(), emptyList(), emptyList())
        source.kids.value = listOf(c)
        source.cloud.value = true
        assertEquals(MapState(listOf(c), cloudOn = true, loaded = true), vm.state.value)
        job.cancel()
    }
}
