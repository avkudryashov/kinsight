package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvertPayloadTest {
    private val key = Primitives.randomBytes(32)
    private val self = DeviceKeys.generate().deviceId
    private val now = 1_760_000_000_000L

    @Test
    fun encodeDecodeRoundTrip() {
        val p = AdvertPayload.build(key, self, byteArrayOf(1, 2, 3, 4), now)
        val bytes = p.encode()
        assertEquals(AdvertPayload.SIZE, bytes.size)
        assertEquals(p, AdvertPayload.decode(bytes))
    }

    @Test
    fun wrongSizeIsNull() {
        assertNull(AdvertPayload.decode(null))
        assertNull(AdvertPayload.decode(ByteArray(9)))
        assertNull(AdvertPayload.decode(ByteArray(11)))
    }

    @Test
    fun buildUsesTheDiscoveryTagsOfTheCurrentWindow() {
        val p = AdvertPayload.build(key, self, byteArrayOf(9, 9, 9, 9), now)
        val w = DiscoveryTags.window(now)
        assertContentEquals(DiscoveryTags.groupTag(key, w), p.groupTag)
        assertContentEquals(DiscoveryTags.deviceTag(key, self.bytes(), w), p.deviceTag)
        assertContentEquals(DiscoveryTags.vectorTag(key, w, byteArrayOf(9, 9, 9, 9)), p.vectorTag)
    }

    @Test
    fun deviceTagValueIsUnsignedBigEndian() {
        val p = AdvertPayload(ByteArray(4), byteArrayOf(0xff.toByte(), 0xfe.toByte()), ByteArray(4))
        assertEquals(0xfffe, p.deviceTagValue)
    }

    @Test
    fun resolvesAcrossNeighbouringWindows() {
        // Fixed inputs (controller ruling N1): random ones collide on the 2-byte tag ~2 in 10,000 runs.
        val fixedKey = ByteArray(32) { (it + 1).toByte() }
        val me = DeviceId("11".repeat(16))
        val other = DeviceId("22".repeat(16))
        val resolver = PeerResolver(fixedKey, listOf(me, other))
        val w = DiscoveryTags.window(now)
        for (dw in -1L..1L) {
            assertEquals(other, resolver.resolve(DiscoveryTags.deviceTag(fixedKey, other.bytes(), w + dw), now), "window offset $dw")
        }
        assertNull(resolver.resolve(DiscoveryTags.deviceTag(fixedKey, other.bytes(), w + 3), now))
        assertNull(resolver.resolve(DiscoveryTags.deviceTag(ByteArray(32) { (100 + it).toByte() }, other.bytes(), w), now))
    }
}
