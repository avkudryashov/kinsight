package app.kinsight.feature.onboarding

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingFlowTest {
    private val admin = DeviceKeys.generate()
    private val groupId = GroupGenesis.create(admin, "Mom").groupId
    private fun invite(role: DeviceRole) = Invite.create(groupId, admin, role, "Mom")
    private val none = Env(nearbyGranted = false, notificationsGranted = false, usageAccessGranted = false)
    private val all = Env(nearbyGranted = true, notificationsGranted = true, usageAccessGranted = true)

    private fun walk(start: OnboardingState, env: Env): List<Step> {
        val steps = mutableListOf(start.step)
        var s = start
        while (s.step != Step.DONE) {
            s = s.copy(step = OnboardingFlow.next(s, env))
            steps += s.step
        }
        return steps
    }

    @Test
    fun joiningAsksForTheCameraRightBeforeTheScanUnlessGrantedOrLinked() {
        val s = OnboardingState(step = Step.NEARBY, path = Path.JOIN, name = "Kid")
        assertEquals(Step.CAMERA, OnboardingFlow.next(s, none))
        assertEquals(Step.SCAN, OnboardingFlow.next(s, none.copy(cameraGranted = true)))
        assertEquals(Step.NEARBY, OnboardingFlow.previous(s.copy(step = Step.CAMERA), none))
        assertEquals(Step.CONSENT, OnboardingFlow.next(s.copy(invite = invite(DeviceRole.CHILD)), none))
        assertFalse(Step.CAMERA in OnboardingFlow.order(OnboardingState(path = Path.CREATE)))
    }

    @Test
    fun createPathAsksEveryPermissionOnce() {
        val s = OnboardingState(step = Step.NAME, path = Path.CREATE, name = "Mom")
        assertEquals(listOf(Step.NAME, Step.PARENT_INFO, Step.NEARBY, Step.NOTIFICATIONS, Step.BATTERY, Step.DONE), walk(s, none))
        assertEquals(listOf(Step.NAME, Step.PARENT_INFO, Step.BATTERY, Step.DONE), walk(s, all))
    }

    @Test
    fun childWithConsentIsAskedForUsageAccessAfterPairing() {
        val s = OnboardingState(step = Step.CONSENT, path = Path.JOIN, name = "Kid", invite = invite(DeviceRole.CHILD), consent = true)
        assertEquals(listOf(Step.CONSENT, Step.PAIR, Step.USAGE_ACCESS, Step.NOTIFICATIONS, Step.BATTERY, Step.DONE), walk(s, none))
    }

    @Test
    fun childWithoutConsentSkipsUsageAccess() {
        val s = OnboardingState(step = Step.CONSENT, path = Path.JOIN, name = "Kid", invite = invite(DeviceRole.CHILD), consent = false)
        assertEquals(listOf(Step.CONSENT, Step.PAIR, Step.NOTIFICATIONS, Step.BATTERY, Step.DONE), walk(s, none))
    }

    @Test
    fun joiningParentSeesWhatItsPhoneDoes() {
        val s = OnboardingState(step = Step.SCAN, path = Path.JOIN, name = "Dad", invite = invite(DeviceRole.PARENT))
        assertEquals(listOf(Step.SCAN, Step.PARENT_INFO, Step.PAIR, Step.BATTERY, Step.DONE), walk(s, all))
    }

    @Test
    fun deepLinkSkipsTheScanner() {
        val s = OnboardingState(step = Step.NAME, path = Path.JOIN, name = "Kid", invite = invite(DeviceRole.CHILD))
        assertEquals(Step.CONSENT, OnboardingFlow.next(s, all))
        assertEquals(Step.NEARBY, OnboardingFlow.next(s, none))
        assertEquals(Step.NAME, OnboardingFlow.previous(s.copy(step = Step.NEARBY)))
    }

    /** Play Stalkerware ruling: only a child shares, so only the child path ever shows consent or usage access. */
    @Test
    fun onlyAChildIsAskedForConsentAndUsageAccess() {
        val parent = OnboardingState(path = Path.JOIN, invite = invite(DeviceRole.PARENT), consent = true)
        val create = OnboardingState(path = Path.CREATE, consent = true)
        for (s in listOf(parent, create)) {
            assertFalse(Step.CONSENT in OnboardingFlow.order(s))
            assertFalse(Step.USAGE_ACCESS in OnboardingFlow.order(s))
        }
        assertTrue(Step.CONSENT in OnboardingFlow.order(OnboardingState(path = Path.JOIN, invite = invite(DeviceRole.CHILD))))
    }

    @Test
    fun previousSkipsSkippedStepsAndNeverReturnsToPairing() {
        val kid = OnboardingState(step = Step.CONSENT, path = Path.JOIN, name = "Kid", invite = invite(DeviceRole.CHILD))
        assertEquals(Step.NEARBY, OnboardingFlow.previous(kid, none))
        assertEquals(Step.NAME, OnboardingFlow.previous(kid, all))
        val after = kid.copy(step = Step.NOTIFICATIONS)
        assertEquals(Step.NOTIFICATIONS, OnboardingFlow.previous(after, none))
        val consenting = kid.copy(step = Step.BATTERY, consent = true)
        assertEquals(Step.BATTERY, OnboardingFlow.previous(consenting, all))
        assertEquals(Step.NOTIFICATIONS, OnboardingFlow.previous(consenting, none))
    }

    @Test
    fun aStepOutsideTheOrderStaysPut() {
        val s = OnboardingState(step = Step.CONSENT, path = Path.CREATE, name = "Mom")
        assertEquals(Step.CONSENT, OnboardingFlow.next(s, none))
    }

    @Test
    fun theJoinedRoleDrivesTheOrderWithoutAnInvite() {
        val kid = OnboardingState(step = Step.PAIR, path = Path.JOIN, joinedRole = DeviceRole.CHILD, consent = true)
        assertEquals(Step.USAGE_ACCESS, OnboardingFlow.next(kid, none))
        val dad = OnboardingState(step = Step.PAIR, path = Path.JOIN, joinedRole = DeviceRole.PARENT, consent = true)
        assertFalse(Step.USAGE_ACCESS in OnboardingFlow.order(dad))
        assertEquals(Step.NOTIFICATIONS, OnboardingFlow.next(dad, none))
    }
}
