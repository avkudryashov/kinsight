package app.kinsight.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.kinsight.core.data.CloudStatus
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SignInState
import app.kinsight.core.data.SummaryMode
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.usage.UsageSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class FakeActions : SettingsActions {
        override val group: Flow<GroupState> = MutableStateFlow(GroupState.NoGroup)
        override val usage: Flow<UsageSettings> = MutableStateFlow(UsageSettings())
        override val prefs: Flow<Prefs> = MutableStateFlow(Prefs())
        override val service: Flow<ServiceStatus> =
            MutableStateFlow(ServiceStatus(ServiceState.STOPPED, true, null, emptyList(), false, null))
        val calls = mutableListOf<String>()
        var fail: String? = null
        var resetGate: CompletableDeferred<Unit>? = null

        private fun call(name: String) {
            calls += name
            if (fail == name) throw IllegalStateException("$name failed")
        }

        override suspend fun setSharing(on: Boolean) = call("sharing:$on")

        override suspend fun setRetention(detailDays: Int, summaryDays: Int) = call("retention:$detailDays/$summaryDays")

        override suspend fun setSummaryMode(mode: SummaryMode) = call("summary:$mode")

        override suspend fun setNoContactHours(hours: Int) = call("noContact:$hours")

        override fun startByUser() = call("startByUser")

        override fun requestStop() = call("stop")

        override fun syncNow() = call("syncNow")

        /** Stands in for LocalReset: status and the running wipe live outside any view model. */
        override val resetStatus = MutableStateFlow<ResetStatus>(ResetStatus.Idle)
        lateinit var appScope: CoroutineScope
        var resetOutcome: Exception? = null

        override fun launchReset() {
            calls += "launchReset"
            resetStatus.value = ResetStatus.Running(ResetKind.NEW_IDENTITY)
            appScope.launch {
                resetGate?.await()
                resetStatus.value = resetOutcome?.let { ResetStatus.Failed(ResetKind.NEW_IDENTITY, it.message!!) } ?: ResetStatus.Idle
            }
        }

        override fun clearResetFailure() {
            calls += "clearResetFailure"
            resetStatus.value = ResetStatus.Idle
        }

        val cloudFlow = MutableStateFlow(CloudStatus())
        override val cloud: Flow<CloudStatus> = cloudFlow
        val signInFlow = MutableStateFlow<SignInState>(SignInState.Idle)
        override val signIn: Flow<SignInState> = signInFlow

        override suspend fun startCloudSignIn(takeOver: Boolean): String? {
            calls += if (takeOver) "startCloudSignIn:takeOver" else "startCloudSignIn"
            return "https://oauth.yandex.ru/authorize?t=1"
        }

        override fun finishCloudSignIn(input: String) {
            calls += "finishCloudSignIn:$input"
        }

        override fun dismissCloudSignIn() {
            calls += "dismissCloudSignIn"
        }

        var handoverAccepted = true

        override suspend fun requestHandover(to: DeviceId): Boolean {
            call("handover:${to.hex}")
            return handoverAccepted
        }
    }

    private val actions = FakeActions()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingSettingsNeverClearsAUserStop() = runTest {
        actions.appScope = backgroundScope
        val vm = SettingsViewModel(actions)
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        assertEquals(true, vm.ui.value.service?.stoppedByUser)
        assertEquals(emptyList(), actions.calls)
        vm.startSync()
        assertEquals(listOf("startByUser"), actions.calls)
    }

    @Test
    fun syncNowAsksTheService() = runTest {
        val vm = SettingsViewModel(actions)
        vm.syncNow()
        assertEquals(listOf("syncNow"), actions.calls)
    }

    @Test
    fun aFailedResetIsShownNeverSilentAndDismissClearsIt() = runTest {
        actions.appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)) // the app scope: outlives any view model
        actions.resetOutcome = IllegalStateException("disk full")
        val vm = SettingsViewModel(actions)
        backgroundScope.launch { vm.ui.collect {} }
        vm.resetIdentity()
        advanceUntilIdle()
        assertEquals(SettingsError.RESET, vm.ui.value.error)
        assertFalse(vm.ui.value.resetting)
        vm.dismissError()
        advanceUntilIdle()
        assertNull(vm.ui.value.error)
        assertEquals(listOf("launchReset", "clearResetFailure"), actions.calls)
    }

    @Test
    fun aViewModelClearedMidResetLeavesTheStatusToTheNextOne() = runTest {
        actions.appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        actions.resetGate = CompletableDeferred()
        actions.resetOutcome = IllegalStateException("disk full")
        val store = ViewModelStore()
        val first = ViewModelProvider.create(store, viewModelFactory { initializer { SettingsViewModel(actions) } })[SettingsViewModel::class]
        first.resetIdentity()
        store.clear() // the screen is gone; the wipe is not
        val second = SettingsViewModel(actions)
        backgroundScope.launch { second.ui.collect {} }
        advanceUntilIdle()
        assertTrue(second.ui.value.resetting)
        actions.resetGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(second.ui.value.resetting)
        assertEquals(SettingsError.RESET, second.ui.value.error)
    }

    @Test
    fun settingFailuresAreShown() = runTest {
        val vm = SettingsViewModel(actions)
        actions.fail = "retention:30/90"
        vm.setRetention(30, 90)
        assertEquals(SettingsError.RETENTION, vm.error.value)
        actions.fail = "sharing:false"
        vm.setSharing(false)
        assertEquals(SettingsError.SHARING, vm.error.value)
        actions.fail = "noContact:6"
        vm.setNoContactHours(6)
        assertEquals(SettingsError.NOTIFICATIONS, vm.error.value)
        actions.fail = null
        vm.setSummaryMode(SummaryMode.WEEKLY)
        assertNull(vm.error.value)
        assertEquals(listOf("retention:30/90", "sharing:false", "noContact:6", "summary:WEEKLY"), actions.calls)
    }

    @Test
    fun theCloudReachesTheUiAndTheSignInGoesThrough() = runTest {
        actions.cloudFlow.value = CloudStatus(available = true, canEnable = true)
        val vm = SettingsViewModel(actions)
        backgroundScope.launch { vm.ui.collect {} }
        advanceUntilIdle()
        assertTrue(vm.ui.value.cloud.canEnable)
        var opened: String? = null
        vm.startCloudSignIn { opened = it }
        advanceUntilIdle()
        assertEquals("https://oauth.yandex.ru/authorize?t=1", opened)
        actions.signInFlow.value = SignInState.Waiting("https://oauth.yandex.ru/authorize?t=1")
        advanceUntilIdle()
        assertEquals(SignInState.Waiting("https://oauth.yandex.ru/authorize?t=1"), vm.ui.value.signIn)
        vm.finishCloudSignIn("1234567")
        advanceUntilIdle()
        vm.dismissCloudSignIn()
        assertEquals(listOf("startCloudSignIn", "finishCloudSignIn:1234567", "dismissCloudSignIn"), actions.calls)
    }

    @Test
    fun takingOverAndTheHandoverReachTheRepository() = runTest {
        val vm = SettingsViewModel(actions)
        var opened: String? = null
        vm.startCloudSignIn(takeOver = true) { opened = it }
        vm.requestHandover(DeviceId("bb".repeat(16)))
        advanceUntilIdle()
        assertEquals(listOf("startCloudSignIn:takeOver", "handover:${"bb".repeat(16)}"), actions.calls)
        assertEquals("https://oauth.yandex.ru/authorize?t=1", opened)
    }

    @Test
    fun aFailedHandoverRequestDoesNotCrash() = runTest {
        actions.fail = "handover:${"bb".repeat(16)}"
        val vm = SettingsViewModel(actions)
        vm.requestHandover(DeviceId("bb".repeat(16)))
        advanceUntilIdle()
        assertEquals(listOf("handover:${"bb".repeat(16)}"), actions.calls)
        assertEquals(SettingsError.HANDOVER, vm.error.value)
    }

    @Test
    fun aRefusedHandoverRequestIsShownAndAnAcceptedOneClearsIt() = runTest {
        actions.handoverAccepted = false
        val vm = SettingsViewModel(actions)
        backgroundScope.launch { vm.ui.collect {} }
        vm.requestHandover(DeviceId("bb".repeat(16)))
        advanceUntilIdle()
        assertEquals(SettingsError.HANDOVER, vm.ui.value.error)
        actions.handoverAccepted = true
        vm.requestHandover(DeviceId("bb".repeat(16)))
        advanceUntilIdle()
        assertNull(vm.ui.value.error)
    }
}
