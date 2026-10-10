package app.kinsight.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.kinsight.core.data.AppPrefs
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class FakeGroup(initial: GroupState, var consent: Boolean = false) : OnboardingGroup {
        val flow = MutableStateFlow(initial)
        val created = mutableListOf<String>()
        override val state: Flow<GroupState> = flow
        override suspend fun createGroup(name: String) {
            created += name
        }
        override suspend fun consentRecorded(): Boolean = consent
    }

    private val dir: File = Files.createTempDirectory("onb").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = AppPrefs.create(File(dir, "p.preferences_pb"), scope)
    private val admin = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(admin, "Mom").groupId
    private fun invite(role: DeviceRole) = Invite.create(groupId, admin, role, "Mom")

    private fun member(name: String, role: Role, self: Boolean = false, isAdmin: Boolean = false) =
        MemberView(DeviceId.fromSignPublicKey(DeviceKeys.generate().sign.publicKey), name, role, isAdmin, self, null)

    private fun ready(self: MemberView, vararg others: MemberView) = GroupState.Ready(groupId, self, listOf(self) + others)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun vm(group: OnboardingGroup, saved: SavedStateHandle = SavedStateHandle(), defaultName: String = "Pixel 8a") =
        OnboardingViewModel(prefs, group, defaultName, saved).also { withTimeout(5_000) { it.restoring.join() } }

    @Test
    fun aChildKilledAfterPairingResumesAfterPairing() = runBlocking<Unit> {
        val group = FakeGroup(ready(member("Kid", Role.CHILD, self = true), member("Mom", Role.PARENT, isAdmin = true)), consent = true)
        val s = vm(group).state.value
        assertEquals(Path.JOIN, s.path)
        assertEquals(DeviceRole.CHILD, s.joinedRole)
        assertTrue(s.consent)
        assertEquals(Step.USAGE_ACCESS, s.step)
    }

    @Test
    fun aParentKilledAfterPairingResumesWithoutConsent() = runBlocking<Unit> {
        val group = FakeGroup(ready(member("Dad", Role.PARENT, self = true), member("Mom", Role.PARENT, isAdmin = true)), consent = true)
        val s = vm(group).state.value
        assertEquals(Path.JOIN, s.path)
        assertFalse(s.consent)
        assertEquals(Step.NOTIFICATIONS, s.step)
    }

    @Test
    fun aCreatedGroupIsNotCreatedTwice() = runBlocking<Unit> {
        val group = FakeGroup(ready(member("Mom", Role.PARENT, self = true, isAdmin = true)))
        val vm = vm(group)
        assertEquals(Path.CREATE, vm.state.value.path)
        assertEquals(Step.DONE, vm.state.value.step)
        var finished = false
        vm.finish { finished = true }
        withTimeout(5_000) { while (!finished) kotlinx.coroutines.delay(10) }
        assertTrue(group.created.isEmpty())
        assertTrue(prefs.current().onboardingDone)
    }

    @Test
    fun finishCreatesTheGroupWhenThereIsNone() = runBlocking<Unit> {
        val group = FakeGroup(GroupState.NoGroup)
        val vm = vm(group)
        vm.choose(Path.CREATE)
        vm.setName("Mom")
        vm.env = Env(nearbyGranted = true, notificationsGranted = true, usageAccessGranted = true)
        repeat(3) { vm.next() }
        assertEquals(Step.DONE, vm.state.value.step)
        var finished = false
        vm.finish { finished = true }
        withTimeout(5_000) { while (!finished) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("Mom"), group.created)
    }

    @Test
    fun savedStateRestoresTheStepBeforePairing() = runBlocking<Unit> {
        val saved = SavedStateHandle()
        val group = FakeGroup(GroupState.NoGroup)
        val first = vm(group, saved)
        first.choose(Path.JOIN)
        first.setName("Kid")
        first.next()
        assertTrue(first.onInviteText(invite(DeviceRole.CHILD).toUri()))
        first.env = Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false)
        first.next()
        assertEquals(Step.CONSENT, first.state.value.step)
        first.consent(true)
        assertEquals(Step.PAIR, first.state.value.step)

        val second = vm(group, saved, defaultName = "Other").state.value
        val before = first.state.value
        assertEquals(before.step, second.step)
        assertEquals(before.path, second.path)
        assertEquals("Kid", second.name)
        assertEquals(before.invite!!.toUri(), second.invite!!.toUri())
        assertTrue(second.consent)
    }

    @Test
    fun choosingCreateDropsTheInviteAndConsent() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onInviteText(invite(DeviceRole.CHILD).toUri())
        vm.consent(true)
        vm.choose(Path.CREATE)
        val s = vm.state.value
        assertNull(s.invite)
        assertFalse(s.consent)
        assertEquals(Step.NAME, s.step)
    }

    @Test
    fun invitesAreIgnoredOncePaired() = runBlocking<Unit> {
        val group = FakeGroup(ready(member("Kid", Role.CHILD, self = true), member("Mom", Role.PARENT, isAdmin = true)))
        val vm = vm(group)
        val before = vm.state.value
        assertFalse(vm.onInviteText(invite(DeviceRole.PARENT).toUri()))
        assertEquals(before, vm.state.value)
    }

    @Test
    fun anInvalidRestoredNameIsReplaced() = runBlocking<Unit> {
        prefs.setDeviceName("Kid‮phone")
        assertEquals("Pixel 8a", vm(FakeGroup(GroupState.NoGroup)).state.value.name)
        val saved = SavedStateHandle(mapOf("onb_name" to "ㅤ"))
        assertEquals("Pixel 8a", vm(FakeGroup(GroupState.NoGroup), saved).state.value.name)
    }
}
