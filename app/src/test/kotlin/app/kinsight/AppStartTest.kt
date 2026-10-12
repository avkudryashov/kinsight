package app.kinsight

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.Role
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import kotlin.test.Test
import kotlin.test.assertEquals

class AppStartTest {
    private val me = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = null)
    private val ready = GroupState.Ready(GroupId("bb".repeat(16)), me, listOf(me))

    @Test
    fun startDestinationFollowsGroupState() {
        assertEquals(Start.LOADING, AppStart.of(GroupState.Loading, Prefs()))
        assertEquals(Start.LOADING, AppStart.of(ready, null))
        assertEquals(Start.KEY_ERROR, AppStart.of(GroupState.KeyError("lost choice"), Prefs()))
        assertEquals(Start.KEY_ERROR, AppStart.of(GroupState.StorageError("bad file"), Prefs(onboardingDone = true)))
        assertEquals(Start.REMOVED, AppStart.of(GroupState.Removed, Prefs(onboardingDone = true)))
        assertEquals(Start.ONBOARDING, AppStart.of(GroupState.NoGroup, Prefs(onboardingDone = true)))
        assertEquals(Start.ONBOARDING, AppStart.of(ready, Prefs(onboardingDone = false))) // joined, permissions still to go
        assertEquals(Start.MAIN, AppStart.of(ready, Prefs(onboardingDone = true)))
    }

    @Test
    fun leavingIsItsOwnRootNotRemoved() {
        // Preflight C5: LEFT_GROUP is signed and travelling; the family must not see «you were removed».
        assertEquals(Start.LEAVING, AppStart.of(GroupState.Leaving, Prefs(onboardingDone = true)))
        assertEquals(Start.LEAVING, AppStart.of(GroupState.Leaving, Prefs(onboardingDone = false)))
        assertEquals(Start.LOADING, AppStart.of(GroupState.Leaving, null))
    }

    @Test
    fun aLocalWipeShowsAtTheRootWhateverTheGroupState() {
        val running = ResetStatus.Running(ResetKind.NEW_IDENTITY)
        val failed = ResetStatus.Failed(ResetKind.FORGET_GROUP, "disk full")
        assertEquals(Start.RESETTING, AppStart.of(GroupState.KeyError("bad"), Prefs(), running))
        assertEquals(Start.RESETTING, AppStart.of(ready, Prefs(onboardingDone = true), running))
        assertEquals(Start.RESETTING, AppStart.of(GroupState.Loading, null, running))
        // The wipe at the end of a leave stays on the leaving screen.
        assertEquals(Start.LEAVING, AppStart.of(GroupState.Leaving, Prefs(), ResetStatus.Running(ResetKind.FORGET_GROUP)))
        assertEquals(Start.RESET_FAILED, AppStart.of(GroupState.Removed, Prefs(onboardingDone = true), failed))
        assertEquals(Start.RESET_FAILED, AppStart.of(ready, Prefs(onboardingDone = true), failed))
        // Idle after a finished wipe: the group is gone and the first run starts again.
        assertEquals(Start.ONBOARDING, AppStart.of(GroupState.NoGroup, Prefs(), ResetStatus.Idle))
    }

    @Test
    fun theRootChangesOnlyWhenItMust() {
        // Loading never replaces a root (a restored root keeps its saved state); the same root is not replaced.
        assertEquals(null, AppStart.nextRoot(Start.ONBOARDING, Start.LOADING))
        assertEquals(null, AppStart.nextRoot(Start.MAIN, Start.MAIN))
        assertEquals(Start.ONBOARDING, AppStart.nextRoot(Start.LOADING, Start.ONBOARDING))
        assertEquals(Start.ONBOARDING, AppStart.nextRoot(null, Start.ONBOARDING))
        // A join wipes the old data (LocalReset.forgetGroup) mid-onboarding: the first run must survive it.
        assertEquals(null, AppStart.nextRoot(Start.ONBOARDING, Start.RESETTING))
        assertEquals(Start.RESET_FAILED, AppStart.nextRoot(Start.ONBOARDING, Start.RESET_FAILED))
        // A reset from Settings or the key error screen blocks the app.
        assertEquals(Start.RESETTING, AppStart.nextRoot(Start.MAIN, Start.RESETTING))
        assertEquals(Start.RESETTING, AppStart.nextRoot(Start.KEY_ERROR, Start.RESETTING))
        assertEquals(Start.ONBOARDING, AppStart.nextRoot(Start.RESETTING, Start.ONBOARDING))
    }
}
