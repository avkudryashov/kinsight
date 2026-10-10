package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.model.DeviceId

/**
 * Service data of our advertisement (spec §8): group tag (4 B), device tag (2 B), short hash of our contiguous
 * version vector (4 B). Legacy advertising has 31 bytes: flags (3) + service data AD with a 128-bit UUID (2+16+10).
 */
class AdvertPayload(val groupTag: ByteArray, val deviceTag: ByteArray, val vectorHash: ByteArray) {
    init {
        require(groupTag.size == 4 && deviceTag.size == 2 && vectorHash.size == 4) { "bad advert field sizes" }
    }

    /** Unsigned big-endian value of the device tag: the smaller one connects (spec §8 «Кто подключается»). */
    val deviceTagValue: Int get() = ((deviceTag[0].toInt() and 0xff) shl 8) or (deviceTag[1].toInt() and 0xff)

    fun encode(): ByteArray = groupTag + deviceTag + vectorHash

    override fun equals(other: Any?) = other is AdvertPayload && encode().contentEquals(other.encode())
    override fun hashCode() = encode().contentHashCode()
    override fun toString() = "Advert(tag=%04x)".format(deviceTagValue)

    companion object {
        const val SIZE = 10

        fun decode(bytes: ByteArray?): AdvertPayload? {
            if (bytes == null || bytes.size != SIZE) return null
            return AdvertPayload(bytes.copyOfRange(0, 4), bytes.copyOfRange(4, 6), bytes.copyOfRange(6, 10))
        }

        fun build(discoveryKey: ByteArray, self: DeviceId, vectorHash: ByteArray, nowMs: Long): AdvertPayload {
            val w = DiscoveryTags.window(nowMs)
            return AdvertPayload(
                DiscoveryTags.groupTag(discoveryKey, w),
                DiscoveryTags.deviceTag(discoveryKey, self.bytes(), w),
                vectorHash,
            )
        }
    }
}
