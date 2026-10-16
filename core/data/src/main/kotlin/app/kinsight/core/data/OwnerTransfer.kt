package app.kinsight.core.data

import android.util.Log
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupId
import app.kinsight.core.model.Membership
import app.kinsight.core.model.MembershipState
import app.kinsight.core.model.StorageOwnerState
import app.kinsight.core.transport.ble.GroupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec §7.3 on the old phone: its transfer invite welcomed [target] with the owner's tokens while the StorageOwner of
 * [generation] with token id [tokenIdHex] was in force. [untilMs]: the invite's expiry, after which an unconfirmed record
 * is dropped; null once the target acknowledged that it took the role.
 */
data class OwnerTransferRecord(val groupHex: String, val target: DeviceId, val tokenIdHex: String, val generation: Long, val untilMs: Long?)

/** For the screens (CloudStatus.ownerTransfer): [PENDING] «Перенос роли…»; [DONE] the new phone holds the role, this one leaves. */
enum class OwnerTransferPhase { PENDING, DONE }

object OwnerTransfers {
    /** The phase of [r] for the group [groupId] with membership [m], or null when there is none (or it expired). */
    fun phase(r: OwnerTransferRecord?, groupId: GroupId, m: MembershipState?, nowMs: Long): OwnerTransferPhase? {
        if (r == null || r.groupHex != groupId.hex) return null
        if (m != null && completed(r, m)) return OwnerTransferPhase.DONE
        if (r.untilMs != null && nowMs > r.untilMs) return null
        return OwnerTransferPhase.PENDING
    }

    /** The StorageOwner in force names the target, is newer, keeps the same token id (rulings Q3) and the target signed it. */
    fun completed(r: OwnerTransferRecord, m: MembershipState): Boolean {
        val owner = m.storageOwner ?: return false
        if (owner.deviceId != r.target || owner.generation <= r.generation || owner.tokenId.hex() != r.tokenIdHex) return false
        return m.effectiveEntries.any { e -> e.author == r.target && e.body.membership_op?.storage_owner?.generation == owner.generation }
    }

    fun record(groupId: GroupId, target: DeviceId, inForce: StorageOwnerState, untilMs: Long?) =
        OwnerTransferRecord(groupId.hex, target, inForce.tokenId.hex(), inForce.generation, untilMs)
}

/**
 * The old phone's side of spec §7.3 «Старый телефон под рукой... 0 нажатий. Старый удаляется из группы»: the transfer
 * invite records its target ([record], [confirm]); [check], run on every membership change while a record exists, makes
 * this phone leave the group by itself (LEFT_GROUP and the local reset, [leave]) once the target's own StorageOwner is in
 * its membership. An incomplete transfer changes nothing: its record expires with the invite. The LEFT_GROUP keeps the
 * token unexposed (OwnerLifecycle.tokenExposed's §7.3 exception), so the new owner goes on with the same token.
 */
class OwnerTransfer(
    private val db: KinsightDatabase,
    private val groups: GroupStore,
    private val keys: suspend () -> DeviceKeys,
    private val state: CloudStateStore,
    /** GroupRepository.leaveAndForget, joined: LEFT_GROUP, a session to carry it, then the identity reset under runLock. */
    private val leave: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val leaving = AtomicBoolean(false)

    suspend fun record(groupId: GroupId, target: DeviceId, inForce: StorageOwnerState, untilMs: Long) =
        state.recordTransfer(OwnerTransfers.record(groupId, target, inForce, untilMs))

    suspend fun confirm(groupId: GroupId, target: DeviceId) = state.confirmTransfer(groupId.hex, target)

    /** Null without a live record; [OwnerTransferPhase.DONE] once the transfer completed (the leave has been run). */
    suspend fun check(): OwnerTransferPhase? {
        val r = state.current().transfer ?: return null
        val group = withContext(io) { groups.read() }
        if (group == null || group.groupId.hex != r.groupHex) {
            state.clearTransfer(r)
            return null
        }
        val m = Membership.compute(group.groupId, RoomEntryStore(db, group.groupId).membershipEntries())
        val phase = OwnerTransfers.phase(r, group.groupId, m, clock())
        if (phase == null) state.clearTransfer(r)
        if (phase != OwnerTransferPhase.DONE || m?.active?.containsKey(keys().deviceId) != true) return phase
        if (!leaving.compareAndSet(false, true)) return phase
        try {
            Log.i(TAG, "the owner role moved to the new phone: leaving the group")
            leave()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "leaving after the owner transfer failed: ${e.javaClass.simpleName}") // retried at the next change
        } finally {
            leaving.set(false)
        }
        return phase
    }

    private companion object {
        const val TAG = "KinsightOwnerTransfer"
    }
}
