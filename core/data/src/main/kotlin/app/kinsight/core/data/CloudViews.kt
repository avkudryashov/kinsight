package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.model.DeviceId
import app.kinsight.core.transport.ble.CloudSync
import app.kinsight.core.transport.cloud.OwnerLeaveBlock
import app.kinsight.core.transport.cloud.OwnerLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    /** Spec §7.2: the owner left or this phone has not heard from it for a week (shown to an admin parent only). */
    val ownerUnavailable: Boolean = false,
    /** Spec §7.1: the owner's name when the owner asked this phone to take the storage. */
    val handoverFrom: String? = null,
    /** This admin parent may take the storage now (asked, or the owner is unavailable). */
    val canTakeOver: Boolean = false,
    /** For the owner: the parents it may hand the storage to. */
    val handoverTargets: List<MemberView> = emptyList(),
    /** For the owner: the parent it asked, until that parent takes the storage. */
    val handoverPendingTo: String? = null,
    /** For the owner: another parent could take the storage, so leaving and the reset wait for the handover (spec §7.1). */
    val handoverNeeded: Boolean = false,
    /**
     * Spec §7.3, on the old phone after its transfer invite: [OwnerTransferPhase.PENDING] while the new phone has not
     * signed the role yet («Перенос роли…»), [OwnerTransferPhase.DONE] once it has (this phone then leaves by itself).
     * Nothing to tap in either; null otherwise.
     */
    val ownerTransfer: OwnerTransferPhase? = null,
    /**
     * This phone owned an older storage and not every phone has switched to the new one yet (OwnerLeaveBlock.SWITCHING):
     * leaving and the reset wait, or the retired token would be lost before it is revoked.
     */
    val cloudSwitching: Boolean = false,
    /**
     * Spec §7.3: this owner may move the storage to a new phone by QR: its vault holds the refresh token of the access in
     * force ([CloudSecrets.transferTokens], the transfer invite's own rule). False for an owner without one: it restores
     * access to the Disk first (that sign-in stores a refresh token).
     */
    val canTransfer: Boolean = false,
    /** Off, because the storage owner turned the cloud off for the family (StorageOwner.off): the folder stays on its Disk. */
    val turnedOff: Boolean = false,
)

object CloudViews {
    /**
     * [nowMs]: this phone's clock, against the time it last heard the owner (rulings Q14). [holdsTransferToken]: the
     * caller's vault read for [s] (CloudViews.holdsTransferToken), so this stays a pure view.
     */
    fun status(
        s: GroupSnapshot,
        state: CloudState,
        available: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        holdsTransferToken: Boolean = false,
    ): CloudStatus {
        val r = s as? GroupSnapshot.Ready ?: return CloudStatus(available = available)
        val me = r.membership.active[r.self] ?: return CloudStatus(available = available)
        val transfer = OwnerTransfers.phase(state.transfer, r.groupId, r.membership, nowMs)
        val owner = r.membership.storageOwner
            ?: return CloudStatus(
                available = available,
                canEnable = available && me.isAdmin && !me.isChild,
                isChild = me.isChild,
                turnedOff = r.membership.storageOff != null,
            )
        val selfIsOwner = owner.deviceId == r.self
        val m = r.membership
        val adminParent = me.isAdmin && !me.isChild
        val unavailable = !selfIsOwner && adminParent && OwnerLifecycle.ownerUnavailable(m, state.ownerHeardFrom(owner.deviceId), nowMs)
        val handoverTo = OwnerLifecycle.handoverTo(m, r.handovers)
        val ownerName = m.active[owner.deviceId]?.displayName ?: m.departed[owner.deviceId]?.member?.displayName
        // Spec §7.1 «Владелец выбирает родителя»: any other parent; one not yet an admin is made one by the request.
        val targets = if (selfIsOwner) SnapshotViews.members(r, nowMs).filter { !it.isSelf && it.role == Role.PARENT } else emptyList()
        // Spec §11: 401 asks the owner to restore and the others to wait; 507 warns the owner only; network trouble is quiet.
        // Spec §7.4: an exposed token asks the owner for a new one (the same sign-in renews it).
        val problem = when (state.problem) {
            CloudProblem.UNAUTHORIZED, CloudProblem.NO_ACCESS -> if (selfIsOwner) CloudProblem.UNAUTHORIZED else CloudProblem.NO_ACCESS
            CloudProblem.STORAGE_FULL -> CloudProblem.STORAGE_FULL.takeIf { selfIsOwner }
            CloudProblem.RENEW_ACCESS, CloudProblem.NETWORK, null -> null
        } ?: CloudProblem.RENEW_ACCESS.takeIf { selfIsOwner && OwnerLifecycle.tokenExposed(m) }
        return CloudStatus(
            available = available,
            on = true,
            ownerName = ownerName,
            selfIsOwner = selfIsOwner,
            canEnable = false,
            isChild = me.isChild,
            lastSyncMs = state.lastSyncMs,
            problem = problem,
            ownerUnavailable = unavailable,
            handoverFrom = ownerName.takeIf { handoverTo == r.self },
            canTakeOver = adminParent && !selfIsOwner && (handoverTo == r.self || unavailable),
            handoverTargets = targets,
            handoverPendingTo = handoverTo?.takeIf { selfIsOwner }?.let { m.active[it]?.displayName },
            handoverNeeded = targets.isNotEmpty(),
            ownerTransfer = transfer,
            cloudSwitching = OwnerLifecycle.leaveBlock(m, r.self, r.heartbeats) == OwnerLeaveBlock.SWITCHING,
            canTransfer = selfIsOwner && holdsTransferToken,
        )
    }

    /**
     * The cloud for the sync service and the one sync status (Task 13): on while this phone is an active member of a
     * group with a storage owner (the conditions of CloudRunner.isOn), with the last successful run of [state].
     */
    fun sync(s: GroupSnapshot, state: CloudState): CloudSync {
        val r = s as? GroupSnapshot.Ready ?: return CloudSync()
        if (r.membership.storageOwner == null || r.self !in r.membership.active) return CloudSync()
        return CloudSync(on = true, lastSyncMs = state.lastSyncMs)
    }

    /** Spec §7.3: this phone owns the storage of [s] and holds the refresh token a transfer invite hands. */
    suspend fun holdsTransferToken(secrets: CloudSecrets, s: GroupSnapshot): Boolean {
        val r = s as? GroupSnapshot.Ready ?: return false
        return try {
            secrets.transferTokens(r.groupId, r.membership, r.self) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KinsightCloud", "cloud tokens unreadable: ${e.javaClass.simpleName}")
            false // the button stays hidden; the transfer invite itself refuses too
        }
    }
}

/** The cloud for the screens: its status and the sign-in. */
class CloudRepository(
    snapshots: Snapshots,
    state: CloudStateStore,
    private val signIn: CloudSignIn,
    private val prefs: AppPrefs,
    private val config: () -> CloudConfig,
    /** The app scope (AppGraph.scope): a finish outlives the screen, like the app link's. */
    private val scope: CoroutineScope,
    /** For [CloudStatus.canTransfer]; null: never offered. */
    private val secrets: CloudSecrets? = null,
    /** The owner's «выключить» (CloudRunner.turnOff). */
    private val cloudOff: suspend () -> CloudOffResult = { CloudOffResult.NOT_OWNER },
) {
    // The vault is read again on every change; a sign-in that stores a refresh token also appends to the log.
    val status: Flow<CloudStatus> = combine(snapshots.snapshots, state.state) { s, st ->
        val holds = secrets?.let { CloudViews.holdsTransferToken(it, s) } == true
        CloudViews.status(s, st, config().configured, holdsTransferToken = holds)
    }

    val signInState: StateFlow<SignInState> = signIn.state

    /** The address to open in the browser, or null when this device may not sign in now ([signInState] says why). */
    suspend fun startSignIn(mode: SignInMode = SignInMode.ENABLE): String? =
        signIn.start(prefs.current().deviceName ?: DEFAULT_DEVICE_NAME, mode)

    /**
     * «Выключить синхронизацию через Яндекс Диск»: the owner turns the cloud off for the family (CloudRunner.turnOff). On
     * the app scope, like a finish: leaving the screen must not cut it between the signed record and its upload.
     */
    suspend fun turnOff(): CloudOffResult = scope.async { cloudOff() }.await()

    /** Spec §7.1: the owner asks [to] to take the storage. */
    suspend fun requestHandover(to: DeviceId): Boolean = signIn.requestHandover(to)

    /**
     * The pasted code, finished on the app scope like the app link (MainActivity): leaving the screen while the Disk is
     * set up must not cancel a turn-on whose code is already spent. The outcome shows in [signInState].
     */
    fun finishSignIn(input: String): Job = scope.launch {
        try {
            signIn.finish(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud sign-in failed to finish: ${e.javaClass.simpleName}")
        }
    }

    fun dismissSignIn() = signIn.dismiss()

    /** The sign-in page answered with an error (CloudSignIn.refused). */
    fun refuseSignIn() = signIn.refused()

    private companion object {
        const val DEFAULT_DEVICE_NAME = "Kinsight"
        const val TAG = "KinsightCloud"
    }
}
