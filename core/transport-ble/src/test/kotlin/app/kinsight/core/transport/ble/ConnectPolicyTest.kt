package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.DiscoveryTags
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectPolicyTest {
    private object Addr : PeerAddress

    private val key = Primitives.randomBytes(32)
    private val a = DeviceKeys.generate().deviceId
    private val b = DeviceKeys.generate().deviceId
    private val now = 1_760_000_000_000L
    private val policy = ConnectPolicy()
    private val minute = 60_000L

    /** Stands for a canonical vector: the policy only feeds it to the HMAC. */
    private fun vector(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun advert(id: DeviceId, v: Int, k: ByteArray = key, at: Long = now) = AdvertPayload.build(k, id, vector(v), at)

    /** [selfV]: our vector, from which [self] was built. */
    private fun decide(
        self: AdvertPayload,
        selfId: DeviceId,
        peer: AdvertPayload,
        peerId: DeviceId?,
        mono: Long = 0,
        fast: Boolean = false,
        selfV: ByteArray = vectorOf(self),
    ): Decision = policy.decide(
        Sighting(Addr, peer, rssi = -60),
        peerId?.let(PeerKey::of) ?: PeerKey.ofTag(peer.deviceTag),
        peerId, self, selfV, selfId, key, now, mono, fast,
    )

    /** The vector behind one of our test adverts (the tests build them from vector(1..9) in the current window). */
    private fun vectorOf(self: AdvertPayload): ByteArray {
        val w = DiscoveryTags.window(now)
        return (0..99).map(::vector).firstOrNull { DiscoveryTags.vectorTag(key, w, it).contentEquals(self.vectorTag) }
            ?: error("unknown test vector")
    }

    private fun assertActs(d: Decision) = assertTrue(d !is Decision.Ignore, "expected an action, got $d")

    @Test
    fun otherGroupIsIgnored() {
        val d = decide(advert(a, 1), a, advert(b, 2, k = Primitives.randomBytes(32)), null)
        assertEquals(Decision.Ignore("other group"), d)
    }

    @Test
    fun selfIsIgnored() {
        assertEquals(Decision.Ignore("self"), decide(advert(a, 1), a, advert(a, 2), a))
    }

    @Test
    fun sameVectorIsIgnored() {
        assertEquals(Decision.Ignore("in sync"), decide(advert(a, 7), a, advert(b, 7), b))
    }

    @Test
    fun sameVectorAdvertisedInTheNeighbouringWindowIsInSync() {
        val w = DiscoveryTags.window(now)
        val late = advert(b, 7, at = (w - 1) * DiscoveryTags.WINDOW_MS)
        val early = advert(b, 7, at = (w + 1) * DiscoveryTags.WINDOW_MS)
        assertEquals(Decision.Ignore("in sync"), decide(advert(a, 7), a, late, b))
        assertEquals(Decision.Ignore("in sync"), decide(advert(a, 7), a, early, b))
        assertActs(decide(advert(a, 8), a, late, b))
    }

    @Test
    fun theVectorFieldOfOneVectorDiffersBetweenWindows() {
        val w = DiscoveryTags.window(now)
        val here = advert(b, 7)
        val next = advert(b, 7, at = (w + 1) * DiscoveryTags.WINDOW_MS)
        assertTrue(!here.vectorTag.contentEquals(next.vectorTag))
    }

    @Test
    fun fastModeConnectsEvenWithTheLargerTag() {
        val sa = advert(a, 1)
        val sb = advert(b, 2)
        val aFirst = if (sa.deviceTagValue != sb.deviceTagValue) sa.deviceTagValue < sb.deviceTagValue else a < b
        val (big, small) = if (aFirst) (sb to b) to (sa to a) else (sa to a) to (sb to b)
        assertIs<Decision.Prepare>(decide(big.first, big.second, small.first, small.second))
        assertIs<Decision.Connect>(decide(big.first, big.second, small.first, small.second, fast = true))
    }

    @Test
    fun fastModeConnectsOnAnUnresolvedTagTie() {
        val group = DiscoveryTags.groupTag(key, DiscoveryTags.window(now))
        val w = DiscoveryTags.window(now)
        val tag = byteArrayOf(0x12, 0x34)
        val self = AdvertPayload(group, tag, DiscoveryTags.vectorTag(key, w, vector(1)))
        val peer = AdvertPayload(group, tag, DiscoveryTags.vectorTag(key, w, vector(2)))
        assertEquals(Decision.Ignore("tag tie"), decide(self, a, peer, null))
        assertIs<Decision.Connect>(decide(self, a, peer, null, fast = true))
    }

    @Test
    fun fastModeStillSkipsInSyncAndBlockedPeers() {
        assertEquals(Decision.Ignore("in sync"), decide(advert(a, 7), a, advert(b, 7), b, fast = true))
        policy.onBlocked(PeerKey.of(b), nowMono = 0)
        assertEquals(Decision.Ignore("blocked"), decide(advert(a, 1), a, advert(b, 2), b, fast = true))
    }

    @Test
    fun lowerTagConnectsAndHigherTagPrepares() {
        val sa = advert(a, 1)
        val sb = advert(b, 2)
        val aFirst = if (sa.deviceTagValue != sb.deviceTagValue) sa.deviceTagValue < sb.deviceTagValue else a < b
        val fromA = decide(sa, a, sb, b)
        val fromB = decide(sb, b, sa, a)
        if (aFirst) {
            assertIs<Decision.Connect>(fromA)
            assertEquals(Decision.Prepare(PeerKey.of(a)), fromB)
        } else {
            assertIs<Decision.Connect>(fromB)
            assertEquals(Decision.Prepare(PeerKey.of(b)), fromA)
        }
    }

    @Test
    fun equalTagsFallBackToDeviceIdOrder() {
        val w = DiscoveryTags.window(now)
        val group = DiscoveryTags.groupTag(key, w)
        val tag = byteArrayOf(0x12, 0x34)
        val sa = AdvertPayload(group, tag, DiscoveryTags.vectorTag(key, w, vector(1)))
        val sb = AdvertPayload(group, tag, DiscoveryTags.vectorTag(key, w, vector(2)))
        val (low, high) = if (a < b) a to b else b to a
        val (lowAdv, highAdv) = if (a < b) sa to sb else sb to sa
        assertIs<Decision.Connect>(decide(lowAdv, low, highAdv, high))
        assertEquals(Decision.Prepare(PeerKey.of(low)), decide(highAdv, high, lowAdv, low))
        assertEquals(Decision.Ignore("tag tie"), decide(lowAdv, low, highAdv, null))
    }

    @Test
    fun cooldownAfterACompletedSessionUnlessFast() {
        policy.onCompleted(PeerKey.of(b), nowMono = 0)
        assertEquals(Decision.Ignore("cooldown"), decide(advert(a, 1), a, advert(b, 2), b, mono = 9 * minute))
        assertActs(decide(advert(a, 1), a, advert(b, 2), b, mono = 9 * minute, fast = true))
        assertActs(decide(advert(a, 1), a, advert(b, 2), b, mono = 10 * minute + 1))
    }

    @Test
    fun failureBacksOffOneMinuteWithoutShorteningACooldown() {
        policy.onFailed(PeerKey.of(b), nowMono = 0)
        assertEquals(Decision.Ignore("cooldown"), decide(advert(a, 1), a, advert(b, 2), b, mono = minute - 1))
        assertActs(decide(advert(a, 1), a, advert(b, 2), b, mono = minute + 1))

        policy.onCompleted(PeerKey.of(b), nowMono = 2 * minute)
        policy.onFailed(PeerKey.of(b), nowMono = 3 * minute)
        assertEquals(Decision.Ignore("cooldown"), decide(advert(a, 1), a, advert(b, 2), b, mono = 5 * minute))
    }

    @Test
    fun blockedPeerIsIgnoredEvenInFastMode() {
        policy.onBlocked(PeerKey.of(b), nowMono = 0)
        assertTrue(policy.isBlocked(PeerKey.of(b), 59 * minute))
        assertEquals(Decision.Ignore("blocked"), decide(advert(a, 1), a, advert(b, 2), b, mono = 59 * minute, fast = true))
        assertActs(decide(advert(a, 1), a, advert(b, 2), b, mono = 60 * minute + 1))
    }
}
