package app.kinsight.core.data

import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.Member
import app.kinsight.core.model.MembershipState
import app.kinsight.core.sync.EntryStore
import app.kinsight.core.sync.HandedAccess
import app.kinsight.core.sync.SessionHandoff
import app.kinsight.core.transport.ble.GroupConfig

/**
 * The cloud hand-off of a Bluetooth session (spec §6 «Раздача» between paired devices, «владелец передаёт access_token»).
 * Only the storage owner hands anything: its own token. A device takes a token only from the peer the membership names as
 * StorageOwner, and only for the storage that record names (SyncSession does not check who sent it). The owner never
 * takes one: its own tokens stay. A vault that still says owner while the record names another device loses that role
 * first ([CloudSecrets.dropStaleOwner]), so the real owner's token is taken.
 */
class CloudSessionHandoff(
    private val secrets: CloudSecrets,
    private val group: GroupConfig,
    private val store: EntryStore,
    private val self: DeviceId,
) : SessionHandoff {
    override suspend fun offer(peer: Member, membership: MembershipState): HandedAccess? {
        val owner = membership.storageOwner ?: return null
        if (owner.deviceId != self) {
            secrets.dropStaleOwner(group.groupId, membership, self)
            return null
        }
        if (membership.active[self]?.isAdmin != true) return null
        val a = secrets.access(store, membership, self, secrets.ring(group)) ?: return null
        return HandedAccess.of(a.generation, a.storageId, a.accessToken, a.expiresAtMs)
    }

    override suspend fun receive(peer: Member, membership: MembershipState, access: HandedAccess) {
        val owner = membership.storageOwner ?: return
        if (owner.deviceId == self || peer.id != owner.deviceId || peer.isChild || !access.isFor(owner)) return
        secrets.dropStaleOwner(group.groupId, membership, self)
        secrets.saveHanded(group.groupId, access)
    }
}
