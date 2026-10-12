package app.kinsight

import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.ResetStatus

enum class Start { LOADING, ONBOARDING, MAIN, KEY_ERROR, REMOVED, LEAVING, RESETTING, RESET_FAILED }

/**
 * Which root screen to show. A local wipe (LocalReset.status) comes first: running it blocks every screen, a failure
 * stays visible with a retry. Leaving is its own root, also through the wipe at its end. Onboarding
 * stays until its DONE step even after the group exists (join path); a finished wipe reads NoGroup → onboarding.
 */
object AppStart {
    fun of(group: GroupState, prefs: Prefs?, reset: ResetStatus = ResetStatus.Idle): Start = when {
        reset is ResetStatus.Failed -> Start.RESET_FAILED
        group == GroupState.Leaving && prefs != null -> Start.LEAVING
        reset is ResetStatus.Running -> Start.RESETTING
        prefs == null || group == GroupState.Loading -> Start.LOADING
        group is GroupState.KeyError || group is GroupState.StorageError -> Start.KEY_ERROR
        group == GroupState.Removed -> Start.REMOVED
        group is GroupState.Ready && prefs.onboardingDone -> Start.MAIN
        else -> Start.ONBOARDING
    }

    /**
     * The root to switch to from [current] (null before the first one) when [AppStart.of] reads [computed]; null keeps
     * the current one. LOADING never replaces a root (after a process restart the restored root keeps its saved
     * state). The first run is not replaced by RESETTING: a join wipes the old data itself (LocalReset.forgetGroup in
     * PairingCoordinator) and must survive it; a failed wipe still shows.
     */
    fun nextRoot(current: Start?, computed: Start): Start? = when {
        computed == Start.LOADING || computed == current -> null
        current == Start.ONBOARDING && computed == Start.RESETTING -> null
        else -> computed
    }
}
