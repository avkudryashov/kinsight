package app.kinsight.core.sync

import app.kinsight.core.model.proto.Hello
import app.kinsight.core.model.proto.Reject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProtocolVersionsTest {
    @Test
    fun rangesAgreeOnTheHighestCommonVersion() {
        assertEquals(3, ProtocolVersions.negotiate(2..3, 2..4))
        assertEquals(2, ProtocolVersions.negotiate(2..2, 2..3))
        assertEquals(2, ProtocolVersions.negotiate(2..3, 1..2))
        assertNull(ProtocolVersions.negotiate(2..2, 3..3))
        assertNull(ProtocolVersions.negotiate(3..4, 1..2))
    }

    @Test
    fun aHelloWithoutTheRangeSpeaksExactlyItsVersion() {
        assertEquals(2..2, ProtocolVersions.of(Hello(protocol_version = 2)))
        assertEquals(2..3, ProtocolVersions.of(Hello(protocol_version = 2, min_protocol_version = 2, max_protocol_version = 3)))
        assertNull(ProtocolVersions.of(Hello(protocol_version = 2, min_protocol_version = 3, max_protocol_version = 2)))
        assertNull(ProtocolVersions.of(Hello(protocol_version = 2, min_protocol_version = 0, max_protocol_version = 3)))
    }

    @Test
    fun sessionsRunOnTheHighestCommonVersion() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        val (ra, rb) = syncPair(a, b, versionsA = 2..3, versionsB = 2..3)
        assertEquals(3, assertIs<SessionResult.Completed>(ra).protocolVersion)
        assertEquals(3, assertIs<SessionResult.Completed>(rb).protocolVersion)
        val (ra2, rb2) = syncPair(a, b, versionsA = 2..3, versionsB = 2..2)
        assertEquals(2, assertIs<SessionResult.Completed>(ra2).protocolVersion)
        assertEquals(2, assertIs<SessionResult.Completed>(rb2).protocolVersion)
    }

    @Test
    fun aRangeStartingAtTheLegacyVersionSyncsWithA072Peer() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        a.publish(a.author.summary(TODAY.toString(), 1))
        val (ra, rb) = syncPair(a, b, versionsA = 2..3, versionsB = 2..2, legacyB = true)
        assertEquals(2, assertIs<SessionResult.Completed>(ra).protocolVersion)
        assertIs<SessionResult.Completed>(rb)
        assertEquals(a.store.versionVector(), b.store.versionVector())
    }

    @Test
    fun disjointRangesAreRejectedOnBothSides() = runBlocking<Unit> {
        val (a, b) = buildGroup(2)
        for ((ra, rb) in listOf(syncPair(a, b, versionsA = 3..3, versionsB = 2..2), syncPair(a, b, versionsA = 3..3, versionsB = 2..2, legacyB = true))) {
            assertEquals(Reject.Reason.PROTOCOL_VERSION, assertIs<SessionResult.Rejected>(ra).reason)
            assertEquals(Reject.Reason.PROTOCOL_VERSION, assertIs<SessionResult.Rejected>(rb).reason)
        }
    }
}
