package app.kinsight.feature.group

import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelsTest {
    private class FakeInvite(val role: Role, val adminName: String) : InviteHandle {
        override val state = MutableStateFlow<InviteState>(InviteState.Starting)
        val calls = mutableListOf<String>()

        override fun confirm() {
            calls += "confirm"
        }

        override fun decline() {
            calls += "decline"
        }

        override fun close() {
            calls += "close"
        }
    }

    private class FakeJoin(val name: String, val consent: Boolean) : JoinHandle {
        override val state = MutableStateFlow<JoinState>(JoinState.Searching)
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val invites = mutableListOf<FakeInvite>()
    private val opener = InviteOpener { _: CoroutineScope, role, name -> FakeInvite(role, name).also { invites += it } }
    private val joins = mutableListOf<FakeJoin>()
    private val starter = JoinStarter { _: CoroutineScope, _, name, consent -> FakeJoin(name, consent).also { joins += it } }
    private val admin = DeviceKeys.generate()
    private val invite = Invite.create(GroupGenesis.create(admin, "Mom").groupId, admin, DeviceRole.CHILD, "Mom")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun inviteOpensWithTheRoleAndThisPhonesNameAndMirrorsTheSession() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        assertEquals(1, invites.size)
        assertEquals(Role.CHILD, invites[0].role)
        assertEquals("Mom", invites[0].adminName)
        invites[0].state.value = InviteState.Showing("kinsight://pair/1/AAAA", 5L, problem = "advertising failed (2), retrying")
        assertEquals(InviteState.Showing("kinsight://pair/1/AAAA", 5L, "advertising failed (2), retrying"), vm.state.value)
    }

    @Test
    fun inviteForwardsTheAdminsDecision() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.PARENT)
        vm.confirm()
        vm.decline()
        assertEquals(listOf("confirm", "decline"), invites[0].calls)
    }

    @Test
    fun aNewCodeClosesTheOldInviteFirst() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        invites[0].state.value = InviteState.Expired
        vm.start()
        assertEquals(listOf("close"), invites[0].calls)
        assertEquals(2, invites.size)
        assertEquals(InviteState.Starting, vm.state.value)
    }

    @Test
    fun leavingTheScreenClosesTheInviteAndComingBackOpensAFreshOne() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        invites[0].state.value = InviteState.Showing("kinsight://pair/1/AAAA", 5L)
        vm.close()
        assertEquals(listOf("close"), invites[0].calls)
        vm.resume()
        assertEquals(2, invites.size)
        vm.resume() // already open: nothing new
        assertEquals(2, invites.size)
    }

    @Test
    fun comingBackAfterTheMemberWasAddedDoesNotOpenAnotherInvite() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        invites[0].state.value = InviteState.Added("Kid")
        vm.close()
        vm.resume()
        assertEquals(1, invites.size)
        assertEquals(InviteState.Added("Kid"), vm.state.value)
    }

    @Test
    fun joinPassesTheNameAndConsentAndMirrorsTheSession() {
        val vm = JoinViewModel(starter, invite, "Kid", consent = true)
        assertEquals("Kid", joins[0].name)
        assertTrue(joins[0].consent)
        joins[0].state.value = JoinState.Waiting("Mom")
        assertEquals(JoinState.Waiting("Mom"), vm.state.value)
    }

    @Test
    fun noConsentJoinsWithoutSharing() {
        JoinViewModel(starter, invite, "Kid", consent = false)
        assertEquals(false, joins[0].consent)
    }

    @Test
    fun joinCancelStopsTheSessionAndRetryStartsANewOne() {
        val vm = JoinViewModel(starter, invite, "Kid", consent = false)
        vm.cancel()
        assertTrue(joins[0].cancelled)
        vm.resume()
        assertEquals(2, joins.size)
        assertEquals(JoinState.Searching, vm.state.value)
        vm.start()
        assertTrue(joins[1].cancelled)
        assertEquals(3, joins.size)
    }

    @Test
    fun joinCancelLetsAStoreInProgressFinish() {
        val vm = JoinViewModel(starter, invite, "Kid", consent = false)
        joins[0].state.value = JoinState.Saving
        vm.cancel()
        assertEquals(false, joins[0].cancelled)
        joins[0].state.value = JoinState.Joined
        vm.resume()
        assertEquals(1, joins.size)
        assertEquals(JoinState.Joined, vm.state.value)
    }
}
