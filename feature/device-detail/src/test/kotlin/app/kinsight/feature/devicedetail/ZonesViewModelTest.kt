package app.kinsight.feature.devicedetail

import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.data.ZoneSave
import app.kinsight.core.data.ZoneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ZonesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSource(var answer: ZoneSave) : ZoneSource {
        val saved = mutableListOf<ZoneDraft>()
        override fun zones(): Flow<List<ZoneView>> = MutableStateFlow(emptyList())
        override fun canEdit(): Flow<Boolean> = flowOf(true)
        override fun lastPoints(): Flow<List<Pair<MemberView, GeoPoint>>> = flowOf(emptyList())
        override suspend fun save(d: ZoneDraft): ZoneSave = answer.also { saved += d }
        override suspend fun delete(id: String): ZoneSave = answer
    }

    @Test
    fun aSavedZoneClosesTheEditorAndARefusedOneSaysSo() = runTest(dispatcher) {
        val source = FakeSource(ZoneSave.SAVED)
        val vm = ZonesViewModel(source)
        var closed = 0
        vm.save(ZoneDraft(null, "Home", 1, 2, 150)) { closed++ }
        assertEquals(1, closed)
        assertNull(vm.failed.value)
        source.answer = ZoneSave.NOT_ALLOWED
        vm.save(ZoneDraft(null, "Home", 1, 2, 150)) { closed++ }
        assertEquals(1, closed)
        assertEquals(ZoneFailure(ZoneSave.NOT_ALLOWED), vm.failed.value)
        vm.dismissError()
        assertNull(vm.failed.value)
    }

    /** Y4 Task 10 review Minor 4: a refused or failing delete is kept as a delete failure; a good one leaves none. */
    @Test
    fun aFailedDeleteIsADeleteFailure() = runTest(dispatcher) {
        val source = FakeSource(ZoneSave.NOT_ALLOWED)
        val vm = ZonesViewModel(source)
        vm.delete("aa".repeat(16))
        assertEquals(ZoneFailure(ZoneSave.NOT_ALLOWED, delete = true), vm.failed.value)
        source.answer = ZoneSave.SAVED
        vm.delete("aa".repeat(16))
        assertNull(vm.failed.value)
    }
}
