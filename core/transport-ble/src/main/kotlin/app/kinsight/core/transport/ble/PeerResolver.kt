package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.model.DeviceId

/**
 * Names the member behind a device tag before any connection. Tags rotate every window and the radio address is a
 * rotating RPA, so the tag checked against every member (current and neighbouring windows) is the only identity
 * there is until the Noise static key. Never use the MAC address.
 */
class PeerResolver(private val discoveryKey: ByteArray, members: Collection<DeviceId>) {
    private val members = members.toList()

    fun resolve(deviceTag: ByteArray, nowMs: Long): DeviceId? {
        val w = DiscoveryTags.window(nowMs)
        return members.firstOrNull { id ->
            (w - 1..w + 1).any { DiscoveryTags.deviceTag(discoveryKey, id.bytes(), it).contentEquals(deviceTag) }
        }
    }
}
