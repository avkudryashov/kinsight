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
        wall: Long = now,
    ): Decision = policy.decide(
        Sighting(Addr, peer, rssi = -60),
        peerId?.let(PeerKey::of) ?: PeerKey.ofTag(peer.deviceTag),
        peerId, self, selfV, selfId, key, wall, mono, fast,
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

    /** (self advert, self id) and (peer advert, peer id), ordered so that the first one has the smaller tag. */
    private fun smallThenBig(): Pair<Pair<AdvertPayload, DeviceId>, Pair<AdvertPayload, DeviceId>> {
        val sa = advert(a, 1)
        val sb = advert(b, 2)
        val aFirst = if (sa.deviceTagValue != sb.deviceTagValue) sa.deviceTagValue < sb.deviceTagValue else a < b
        return if (aFirst) (sa to a) to (sb to b) else (sb to b) to (sa to a)
    }

    /** The canonical vectors of a pair as an empty session exchanged them (ours = [self], theirs = [peer]). */
    private fun empty(self: AdvertPayload, peer: AdvertPayload, peerId: DeviceId, at: Long) =
        policy.onCompleted(PeerKey.of(peerId), nowMono = at, empty = true, ourVector = vectorOf(self), peerVector = vectorOf(peer))

    @Test
    fun normalModeSkipsAPeerWhoseLastSessionWasEmptyForUpToTheCeiling() {
        val (small, big) = smallThenBig()
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 0))
        empty(small.first, big.first, big.second, at = 1_000)
        assertEquals(Decision.Ignore("cooldown"), decide(small.first, small.second, big.first, big.second, mono = 2_000))
        assertEquals(
            Decision.Ignore("nothing new"),
            decide(small.first, small.second, big.first, big.second, mono = 1_000 + ConnectPolicy.COOLDOWN_MS),
        )
        assertEquals(
            Decision.Ignore("nothing new"),
            decide(small.first, small.second, big.first, big.second, mono = 1_000 + ConnectPolicy.MAX_SILENCE_MS - 1),
        )
        // Liveness: one session per ceiling even when nothing moves.
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 1_000 + ConnectPolicy.MAX_SILENCE_MS))
    }

    @Test
    fun normalModeKeepsSkippingAcrossWindowBoundariesUntilTheCeiling() {
        val (small, big) = smallThenBig()
        empty(small.first, big.first, big.second, at = 0)
        // Real clock: the advertised vector tags rotate every 15 min window, the vectors behind them do not.
        for (minutes in listOf(16L, 31L, 39L)) {
            val wall = now + minutes * minute
            val d = decide(
                advert(small.second, vectorOf(small.first).let { ByteBuffer.wrap(it).int }, at = wall), small.second,
                advert(big.second, ByteBuffer.wrap(vectorOf(big.first)).int, at = wall), big.second,
                mono = minutes * minute, wall = wall, selfV = vectorOf(small.first),
            )
            assertEquals(Decision.Ignore("nothing new"), d, "after $minutes min")
        }
        val wall = now + 40 * minute
        assertActs(
            decide(
                advert(small.second, ByteBuffer.wrap(vectorOf(small.first)).int, at = wall), small.second,
                advert(big.second, ByteBuffer.wrap(vectorOf(big.first)).int, at = wall), big.second,
                mono = 40 * minute, wall = wall, selfV = vectorOf(small.first),
            ),
        )
    }

    @Test
    fun normalModeDialsAfterTheCooldownWhenOurVectorChanged() {
        val (small, big) = smallThenBig()
        empty(small.first, big.first, big.second, at = 1_000)
        assertIs<Decision.Connect>(
            decide(small.first, small.second, big.first, big.second, mono = 1_000 + ConnectPolicy.COOLDOWN_MS, selfV = vector(5)),
        )
    }

    @Test
    fun normalModeActsAfterTheCooldownWhenThePeersVectorChanged() {
        val (small, big) = smallThenBig()
        empty(small.first, big.first, big.second, at = 1_000)
        val moved = advert(big.second, 7)
        assertActs(decide(small.first, small.second, moved, big.second, mono = 1_000 + ConnectPolicy.COOLDOWN_MS))
    }

    @Test
    fun theSkipHoldsWhenTheRolesFlipAfterTagRotation() {
        val (small, big) = smallThenBig()
        // The empty session was dialled by `small`; the side that was the responder now has the smaller tag.
        empty(big.first, small.first, small.second, at = 1_000)
        assertEquals(
            Decision.Ignore("nothing new"),
            decide(big.first, big.second, small.first, small.second, mono = 1_000 + ConnectPolicy.COOLDOWN_MS),
        )
    }

    @Test
    fun normalModeKeepsDialingAfterASessionThatMovedData() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(big.second)
        policy.onCompleted(peer, nowMono = 1_000, empty = false, ourVector = vectorOf(small.first), peerVector = vectorOf(big.first))
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 1_000 + ConnectPolicy.COOLDOWN_MS))
    }

    @Test
    fun theCeilingStaysBelowTheSmallestNoContactSetting() {
        // noContactHours = 1 (AppPrefs 1..168): gap = ceiling + scan batching and session time must stay under it.
        assertTrue(ConnectPolicy.MAX_SILENCE_MS + 15 * minute <= 60 * minute)
    }

    @Test
    fun fastModeLetsTheSmallerTagDialFirstAndTheLargerOneOnlyAfterTheYield() {
        val (small, big) = smallThenBig()
        assertIs<Decision.Prepare>(decide(big.first, big.second, small.first, small.second))
        // Fast: the larger tag yields for FAST_YIELD_MS (the peer may be in fast mode too and dial first)...
        assertIs<Decision.Prepare>(decide(big.first, big.second, small.first, small.second, mono = 0, fast = true))
        assertIs<Decision.Prepare>(
            decide(big.first, big.second, small.first, small.second, mono = ConnectPolicy.FAST_YIELD_MS - 1, fast = true),
        )
        // ...then dials past its cooldown: a peer in normal mode would sit out its own.
        assertIs<Decision.Connect>(
            decide(big.first, big.second, small.first, small.second, mono = ConnectPolicy.FAST_YIELD_MS, fast = true),
        )
    }

    @Test
    fun fastModeSmallerTagDialsAtOnce() {
        val (small, big) = smallThenBig()
        policy.onCompleted(PeerKey.of(big.second), nowMono = -60_000) // in cooldown
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 0, fast = true))
    }

    @Test
    fun fastModeLargerTagDoesNotDialWhenThePeerConnectedDuringTheYield() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(small.second)
        assertIs<Decision.Prepare>(decide(big.first, big.second, small.first, small.second, mono = 0, fast = true))
        policy.onCompleted(peer, nowMono = 1_000) // the peer dialed us (inbound)
        val d = decide(big.first, big.second, small.first, small.second, mono = 60_000, fast = true)
        assertTrue(d !is Decision.Connect, "the peer already connected in this fast window: $d")
    }

    @Test
    fun fastModeSpacesDialsToOnePeerByFiveSeconds() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(big.second)
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 0, fast = true))
        policy.onFailed(peer, nowMono = 500)
        assertEquals(
            Decision.Ignore("fast spacing"),
            decide(small.first, small.second, big.first, big.second, mono = ConnectPolicy.FAST_SPACING_MS - 1, fast = true),
        )
        assertIs<Decision.Connect>(
            decide(small.first, small.second, big.first, big.second, mono = ConnectPolicy.FAST_SPACING_MS, fast = true),
        )
    }

    @Test
    fun fastModeFallsBackToTheBackoffAfterThreeFailedDials() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(big.second)
        var t = 0L
        repeat(ConnectPolicy.FAST_MAX_FAILURES) {
            assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = t, fast = true))
            policy.onFailed(peer, nowMono = t)
            t += ConnectPolicy.FAST_SPACING_MS
        }
        assertEquals(Decision.Ignore("cooldown"), decide(small.first, small.second, big.first, big.second, mono = t, fast = true))
        // A new fast window (another «Sync now») starts the count over.
        policy.onFastStart()
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = t, fast = true))
    }

    @Test
    fun fastModeStopsDialingAPeerWhoseLastSessionWasEmptyUntilAVectorChanges() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(big.second)
        // A child (showOthers off) never takes a parent's own entries: the vectors never match (emulator run, 1 empty
        // session per second for the whole fast minute).
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 0, fast = true))
        empty(small.first, big.first, big.second, at = 1_000)
        assertEquals(
            Decision.Ignore("nothing new"),
            decide(small.first, small.second, big.first, big.second, mono = 30_000, fast = true),
        )
        // Our vector changed (a new entry): there is something to send again.
        val selfV = vector(5)
        val changed = AdvertPayload(small.first.groupTag, small.first.deviceTag, small.first.vectorTag)
        assertIs<Decision.Connect>(
            decide(changed, small.second, big.first, big.second, mono = 30_000, fast = true, selfV = selfV),
        )
    }

    @Test
    fun aSessionThatMovedDataDoesNotSilenceThePeer() {
        val (small, big) = smallThenBig()
        val peer = PeerKey.of(big.second)
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 0, fast = true))
        policy.onCompleted(peer, nowMono = 1_000, empty = false, ourVector = vectorOf(small.first), peerVector = vectorOf(big.first))
        assertIs<Decision.Connect>(decide(small.first, small.second, big.first, big.second, mono = 30_000, fast = true))
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
