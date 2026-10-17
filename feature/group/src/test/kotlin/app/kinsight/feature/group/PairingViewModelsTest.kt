package app.kinsight.feature.group

import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.transport.ble.RadioProblem
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

    private class FakeJoin(val name: String, val consent: Boolean, val fresh: Boolean = false) : JoinHandle {
        override val state = MutableStateFlow<JoinState>(JoinState.Searching)
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val invites = mutableListOf<FakeInvite>()
    private val opener = InviteOpener { _: CoroutineScope, role, name -> FakeInvite(role, name).also { invites += it } }
    private val joins = mutableListOf<FakeJoin>()
    private val starter = JoinStarter { _: CoroutineScope, _, name, consent, fresh -> FakeJoin(name, consent, fresh).also { joins += it } }
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
        val busy = RadioProblem(RadioProblem.Kind.ADVERTISE_FAILED, retrying = true, code = 2)
        invites[0].state.value = InviteState.Showing("kinsight://pair/1/AAAA", 5L, problem = busy)
        assertEquals(InviteState.Showing("kinsight://pair/1/AAAA", 5L, busy), vm.state.value)
    }

    /** Y3 parked minor: the owner's «Перенести на новый телефон» reaches PairingCoordinator.invite(transferOwner = true). */
    @Test
    fun theTransferInviteAsksForTheOwnerTransfer() {
        val asked = mutableListOf<Boolean>()
        val inviter = Inviter { scope, role, name, transferOwner -> asked += transferOwner; opener.open(scope, role, name) }
        val vm = InviteViewModel(inviter, { "Mom" }, Role.PARENT, transfer = true)
        vm.start() // a fresh code after coming back: still the transfer
        assertEquals(listOf(true, true), asked)
        InviteViewModel(inviter, { "Mom" }, Role.PARENT, transfer = false)
        assertEquals(listOf(true, true, false), asked)
    }

    @Test
    fun inviteForwardsTheAdminsDecision() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.PARENT)
        invites[0].state.value = InviteState.Confirm("kinsight://pair/1/AAAA", 5L, "Dad", Role.PARENT)
        vm.decline()
        vm.confirm()
        vm.decline() // Add was tapped: the answer is given
        assertEquals(listOf("decline", "confirm"), invites[0].calls)
    }

    @Test
    fun addingShowsProgressUntilTheInviteEnds() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        invites[0].state.value = InviteState.Confirm("kinsight://pair/1/AAAA", 5L, "Kid", Role.CHILD)
        assertEquals(false, vm.confirming.value)
        vm.confirm()
        assertTrue(vm.confirming.value)
        vm.confirm() // a second tap is not a second answer
        assertEquals(listOf("confirm"), invites[0].calls)
        invites[0].state.value = InviteState.Added("Kid")
        assertEquals(false, vm.confirming.value)
    }

    @Test
    fun aFailedAddEndsTheProgress() {
        val vm = InviteViewModel(opener, { "Mom" }, Role.CHILD)
        invites[0].state.value = InviteState.Confirm("kinsight://pair/1/AAAA", 5L, "Kid", Role.CHILD)
        vm.confirm()
        invites[0].state.value = InviteState.Failed(app.kinsight.core.data.PairFailure.LINK)
        assertEquals(false, vm.confirming.value)
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
    fun resetAndRetryStartsAFreshIdentityJoinWithTheSameInvite() {
        val vm = JoinViewModel(starter, invite, "Kid", consent = true)
        assertEquals(false, joins[0].fresh)
        joins[0].state.value = JoinState.Failed(app.kinsight.core.data.PairFailure.REMOVED_BEFORE)
        vm.resetAndRetry()
        assertTrue(joins[0].cancelled)
        assertEquals(2, joins.size)
        assertTrue(joins[1].fresh)
        assertEquals("Kid" to true, joins[1].name to joins[1].consent)
        assertEquals(JoinState.Searching, vm.state.value)
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
