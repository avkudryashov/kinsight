package app.kinsight.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * The cloud as the settings screen shows it. [available]: this build has a Yandex client. [problem]: what this device's
 * user can act on or must know (spec §11), already filtered by role.
 */
data class CloudStatus(
    val available: Boolean = false,
    val on: Boolean = false,
    val ownerName: String? = null,
    val selfIsOwner: Boolean = false,
    val canEnable: Boolean = false,
    val isChild: Boolean = false,
    val lastSyncMs: Long? = null,
    val problem: CloudProblem? = null,
)

object CloudViews {
    fun status(s: GroupSnapshot, state: CloudState, available: Boolean): CloudStatus {
        val r = s as? GroupSnapshot.Ready ?: return CloudStatus(available = available)
        val me = r.membership.active[r.self] ?: return CloudStatus(available = available)
        val owner = r.membership.storageOwner
            ?: return CloudStatus(available = available, canEnable = available && me.isAdmin && !me.isChild, isChild = me.isChild)
        val selfIsOwner = owner.deviceId == r.self
        // Spec §11: 401 asks the owner to restore and the others to wait; 507 warns the owner only; network trouble is quiet.
        val problem = when (state.problem) {
            CloudProblem.UNAUTHORIZED, CloudProblem.NO_ACCESS -> if (selfIsOwner) CloudProblem.UNAUTHORIZED else CloudProblem.NO_ACCESS
            CloudProblem.STORAGE_FULL -> CloudProblem.STORAGE_FULL.takeIf { selfIsOwner }
            CloudProblem.NETWORK, null -> null
        }
        return CloudStatus(
            available = available,
            on = true,
            ownerName = r.membership.active[owner.deviceId]?.displayName,
            selfIsOwner = selfIsOwner,
            canEnable = false,
            isChild = me.isChild,
            lastSyncMs = state.lastSyncMs,
            problem = problem,
        )
    }
}

/** The cloud for the screens: its status and the sign-in. */
class CloudRepository(
    snapshots: Snapshots,
    state: CloudStateStore,
    private val signIn: CloudSignIn,
    private val prefs: AppPrefs,
    private val config: () -> CloudConfig,
) {
    val status: Flow<CloudStatus> = combine(snapshots.snapshots, state.state) { s, st -> CloudViews.status(s, st, config().configured) }

    val signInState: StateFlow<SignInState> = signIn.state

    /** The address to open in the browser, or null when this device may not sign in now ([signInState] says why). */
    suspend fun startSignIn(): String? = signIn.start(prefs.current().deviceName ?: DEFAULT_DEVICE_NAME)

    suspend fun finishSignIn(input: String) {
        signIn.finish(input)
    }

    fun dismissSignIn() = signIn.dismiss()

    private companion object {
        const val DEFAULT_DEVICE_NAME = "Kinsight"
    }
}
