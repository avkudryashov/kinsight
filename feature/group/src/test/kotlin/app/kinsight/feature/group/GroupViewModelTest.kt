package app.kinsight.feature.group

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.LeaveError
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class GroupViewModelTest {
    private class FakeActions : GroupActions {
        override val state: Flow<GroupState> = emptyFlow()
        override val leaving = MutableStateFlow(false)
        override val leaveError = MutableStateFlow<LeaveError?>(null)
        var failGrant = false
        var failRemove = false
        val calls = mutableListOf<String>()

        override suspend fun grantAdmin(id: DeviceId) {
            calls += "grant:${id.hex}"
            if (failGrant) throw IllegalStateException("no group: cannot grant admin")
        }

        override suspend fun remove(id: DeviceId) {
            calls += "remove:${id.hex}"
            if (failRemove) throw IllegalStateException("no group: cannot remove a member")
        }

        override fun leaveAndForget() {
            calls += "leave"
        }

        override fun dismissLeaveError() {
            calls += "dismissLeave"
            leaveError.value = null
        }
    }

    private val dad = DeviceId("bb".repeat(16))
    private val actions = FakeActions()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun grantFailureWithoutAGroupIsShownNotThrown() = runTest {
        actions.failGrant = true
        val vm = GroupViewModel(actions)
        vm.grantAdmin(dad)
        assertEquals(GroupError.GrantFailed, vm.error.value)
        vm.dismissError()
        assertNull(vm.error.value)
    }

    @Test
    fun removeFailureWithoutAGroupIsShownNotThrown() = runTest {
        actions.failRemove = true
        val vm = GroupViewModel(actions)
        vm.remove(dad)
        assertEquals(GroupError.RemoveFailed, vm.error.value)
        assertEquals(listOf("remove:${dad.hex}"), actions.calls)
    }

    @Test
    fun successfulActionsLeaveNoError() = runTest {
        val vm = GroupViewModel(actions)
        vm.grantAdmin(dad)
        vm.remove(dad)
        assertNull(vm.error.value)
        assertEquals(listOf("grant:${dad.hex}", "remove:${dad.hex}"), actions.calls)
    }

    @Test
    fun leaveGoesThroughLeaveAndForgetAndItsErrorsSurface() = runTest {
        val vm = GroupViewModel(actions)
        vm.leave()
        assertEquals(listOf("leave"), actions.calls)
        actions.leaveError.value = LeaveError.WIPE_FAILED
        assertEquals(GroupError.Leave(LeaveError.WIPE_FAILED), vm.error.value)
        vm.dismissError()
        assertNull(vm.error.value)
        assertEquals(listOf("leave", "dismissLeave"), actions.calls)
    }
}
