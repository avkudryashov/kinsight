package app.kinsight.feature.devicedetail

import app.kinsight.core.data.ChildLocation
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.maps.Cell
import app.kinsight.core.maps.StreetMaps
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.SyncService
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
        val admin = MutableStateFlow(false)
        val lastSync = MutableStateFlow<Long?>(null)
        var syncs = 0
        override fun canEditZones(): Flow<Boolean> = admin
        override fun lastSyncMs(): Flow<Long?> = lastSync
        override fun syncNow() {
            syncs++
        }
        val streetsFlow = MutableStateFlow(StreetMaps.NONE)
        override fun streets(): Flow<StreetMaps> = streetsFlow
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

    @Test
    fun openingTheTabSyncsOnceAndSaysItIsUpdatingUntilANewerSync() = runTest(dispatcher) {
        val source = FakeSource()
        var clock = 1_000L
        val vm = MapViewModel(source, clock = { clock })
        val job = launch { vm.state.collect {} }
        source.ready.value = true
        assertEquals(0, source.syncs, "nothing before the tab opens")
        vm.onOpen()
        assertEquals(1, source.syncs)
        assertTrue(vm.state.value.searching)
        vm.onOpen() // the same opening composed again (a rotation): no second request
        assertEquals(1, source.syncs)
        clock = 2_000L
        source.lastSync.value = 1_500L
        assertFalse(vm.state.value.searching, "a sync newer than the request ends the search")
        job.cancel()
    }

    @Test
    fun theSearchEndsWithTheFastWindowAndTheNextOpeningSyncsAgain() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = MapViewModel(source, clock = { 1_000L })
        val job = launch { vm.state.collect {} }
        vm.onOpen()
        assertTrue(vm.state.value.searching)
        testScheduler.advanceTimeBy(SyncService.FAST_MS + 1)
        assertFalse(vm.state.value.searching)
        vm.onOpen()
        assertEquals(2, source.syncs)
        job.cancel()
    }

    @Test
    fun anAdminMayEditTheZones() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = MapViewModel(source)
        val job = launch { vm.state.collect {} }
        assertFalse(vm.state.value.canEditZones)
        source.admin.value = true
        assertTrue(vm.state.value.canEditZones)
        job.cancel()
    }

    @Test
    fun theStreetMapReachesTheState() = runTest(dispatcher) {
        val source = FakeSource()
        val vm = MapViewModel(source)
        val job = launch { vm.state.collect {} }
        val s = StreetMaps(mapOf(Cell(510, 389) to "/maps/z10-510-389.pmtiles"), emptySet(), downloading = false)
        source.streetsFlow.value = s
        assertEquals(s, vm.state.value.streets)
        job.cancel()
    }
}
