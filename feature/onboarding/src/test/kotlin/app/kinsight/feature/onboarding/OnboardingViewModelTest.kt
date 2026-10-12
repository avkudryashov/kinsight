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
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class FakeGroup(initial: GroupState, var consent: Boolean = false) : OnboardingGroup {
        val flow = MutableStateFlow(initial)
        val created = mutableListOf<String>()

        /** The group file: what the state flow may not show yet (a stale replay). */
        var exists = initial is GroupState.Ready
        override val state: Flow<GroupState> = flow
        override suspend fun createGroup(name: String) {
            check(!exists) { "a group already exists" }
            created += name
            exists = true
        }
        override suspend fun groupExists(): Boolean = exists
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
        vm.onResumed(Env(nearbyGranted = true, notificationsGranted = true, usageAccessGranted = true))
        repeat(3) { vm.next() }
        assertEquals(Step.DONE, vm.state.value.step)
        var finished = false
        vm.finish { finished = true }
        withTimeout(5_000) { while (!finished) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("Mom"), group.created)
    }

    /** Re-review item 2: a retry after the group was made decides on the group file, not on a stale group state. */
    @Test
    fun aRetryWithAStaleStateDoesNotCreateTheGroupAgain() = runBlocking<Unit> {
        val group = FakeGroup(GroupState.NoGroup)
        val vm = vm(group)
        vm.choose(Path.CREATE)
        vm.setName("Mom")
        vm.onResumed(Env(nearbyGranted = true, notificationsGranted = true, usageAccessGranted = true))
        repeat(3) { vm.next() }
        group.exists = true // made by an earlier try; the state flow still replays NoGroup
        var finished = false
        vm.finish { finished = true }
        withTimeout(5_000) { while (!finished && vm.message.value == null) kotlinx.coroutines.delay(10) }
        assertNull(vm.message.value)
        assertTrue(group.created.isEmpty())
        assertTrue(finished)
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
        first.onResumed(Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false))
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
    fun aStoredNameReplacesTheModelWhenNothingIsSaved() = runBlocking<Unit> {
        // The debug DEFAULT_NAME hook stores the name before the first run: it wins over Build.MODEL.
        assertEquals("Pixel 8a", vm(FakeGroup(GroupState.NoGroup)).state.value.name)
        prefs.setDeviceName("Галина")
        assertEquals("Галина", vm(FakeGroup(GroupState.NoGroup)).state.value.name)
        val saved = SavedStateHandle(mapOf("onb_name" to "Kid"))
        assertEquals("Kid", vm(FakeGroup(GroupState.NoGroup), saved).state.value.name)
    }

    @Test
    fun anInvalidRestoredNameIsReplaced() = runBlocking<Unit> {
        prefs.setDeviceName("Kid‮phone")
        assertEquals("Pixel 8a", vm(FakeGroup(GroupState.NoGroup)).state.value.name)
        val saved = SavedStateHandle(mapOf("onb_name" to "ㅤ"))
        assertEquals("Pixel 8a", vm(FakeGroup(GroupState.NoGroup), saved).state.value.name)
    }

    private val granted = Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false, cameraGranted = true)
    private val all = Env(nearbyGranted = true, notificationsGranted = true, usageAccessGranted = true, cameraGranted = true)

    @Test
    fun theCameraStepGoesOnWhateverTheAnswer() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.JOIN)
        vm.next()
        val nearbyOnly = Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false)
        vm.onNearbyResult(nearbyOnly, blocked = false)
        assertEquals(Step.CAMERA, vm.state.value.step)
        vm.onCameraResult(nearbyOnly) // denied: the system camera app still works
        assertEquals(Step.SCAN, vm.state.value.step)
        vm.onCameraResult(nearbyOnly.copy(cameraGranted = true)) // a late answer moves nothing
        assertEquals(Step.SCAN, vm.state.value.step)
    }

    @Test
    fun aCameraGrantedInSettingsSkipsTheDisclosureOnResume() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.JOIN)
        vm.next()
        val nearbyOnly = Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false)
        vm.onNearbyResult(nearbyOnly, blocked = false)
        assertEquals(Step.CAMERA, vm.state.value.step)
        vm.onResumed(nearbyOnly.copy(cameraGranted = true))
        assertEquals(Step.SCAN, vm.state.value.step)
    }

    @Test
    fun aDeepLinkOnTheCameraDisclosureGoesOnWithoutTheCamera() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.JOIN)
        vm.next()
        vm.onNearbyResult(Env(nearbyGranted = true, notificationsGranted = false, usageAccessGranted = false), blocked = false)
        assertEquals(Step.CAMERA, vm.state.value.step)
        assertTrue(vm.onInviteText(invite(DeviceRole.CHILD).toUri()))
        assertEquals(Step.CONSENT, vm.state.value.step)
    }

    @Test
    fun aNearbyGrantAdvancesOnce() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.JOIN)
        vm.next()
        assertEquals(Step.NEARBY, vm.state.value.step)
        vm.onNearbyResult(granted, blocked = false)
        assertEquals(Step.SCAN, vm.state.value.step)
        vm.onResumed(granted)
        assertEquals(Step.SCAN, vm.state.value.step)
    }

    @Test
    fun aNearbyGrantOnTheCreatePathDoesNotSkipNotifications() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.CREATE)
        vm.next()
        vm.next()
        assertEquals(Step.NEARBY, vm.state.value.step)
        vm.onNearbyResult(granted, blocked = false)
        assertEquals(Step.NOTIFICATIONS, vm.state.value.step)
        vm.onResumed(granted)
        assertEquals(Step.NOTIFICATIONS, vm.state.value.step)
    }

    @Test
    fun aGrantInSettingsAdvancesOnResume() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.onResumed(Env.NOTHING)
        vm.choose(Path.JOIN)
        vm.next()
        vm.onResumed(granted)
        assertEquals(Step.SCAN, vm.state.value.step)
    }

    @Test
    fun notNowKeepsTheSettingsWayWhenNearbyIsBlocked() = runBlocking<Unit> {
        val vm = vm(FakeGroup(GroupState.NoGroup))
        vm.choose(Path.JOIN)
        vm.next()
        vm.onNearbyResult(Env.NOTHING, blocked = true)
        assertTrue(vm.nearbyBlocked.value)
        vm.onNearbyNotNow()
        assertTrue(vm.nearbyBlocked.value)
        assertEquals(Step.NEARBY, vm.state.value.step)
    }

    @Test
    fun theResumeStepWaitsForTheRealPermissions() = runBlocking<Unit> {
        val group = FakeGroup(ready(member("Kid", Role.CHILD, self = true), member("Mom", Role.PARENT, isAdmin = true)), consent = true)
        val vm = vm(group)
        vm.onResumed(all)
        assertEquals(Step.BATTERY, vm.state.value.step)
    }

    @Test
    fun aRestoredPathNeverGoesBackToWelcome() = runBlocking<Unit> {
        val created = vm(FakeGroup(ready(member("Mom", Role.PARENT, self = true, isAdmin = true), member("Kid", Role.CHILD))))
        created.onResumed(Env.NOTHING)
        assertEquals(Path.CREATE, created.state.value.path)
        repeat(10) { created.back() }
        assertTrue(created.state.value.step !in setOf(Step.WELCOME, Step.NAME, Step.PARENT_INFO), "${created.state.value.step}")

        val joined = vm(FakeGroup(ready(member("Kid", Role.CHILD, self = true), member("Mom", Role.PARENT, isAdmin = true))))
        joined.onResumed(Env.NOTHING)
        repeat(10) { joined.back() }
        assertTrue(joined.state.value.step !in setOf(Step.WELCOME, Step.NAME, Step.PAIR, Step.CONSENT), "${joined.state.value.step}")
    }

    @Test
    fun theScreenWaitsForTheRestore() = runBlocking<Unit> {
        val group = FakeGroup(GroupState.Loading)
        val vm = OnboardingViewModel(prefs, group, "Pixel 8a", SavedStateHandle())
        assertFalse(vm.restored.value)
        group.flow.value = GroupState.NoGroup
        withTimeout(5_000) { vm.restoring.join() }
        assertTrue(vm.restored.value)
    }
}
