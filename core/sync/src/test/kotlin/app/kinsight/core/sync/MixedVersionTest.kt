package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.proto.StateEvent
import kotlinx.coroutines.runBlocking
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A family on three versions (audit A-1): a future node (range 2..3) writes what only it understands, a current node
 * (2..2) keeps and relays it, a node with a 0.7.2-style Hello receives it through the current one. The legacy node
 * runs this build's validator: the test pins the Hello path and the current validator, not 0.7.2's.
 */
class MixedVersionTest {
    /** A payload field this build does not know (EntryBody field 30, length-delimited, holding field 1 = 1). */
    private fun EntryAuthor.futureKind(): DecodedEntry = create {
        it.copy(unknownFields = Buffer().writeByte(0xF2).writeByte(0x01).writeByte(2).writeByte(0x08).writeByte(0x01).readByteString())
    }

    /** A StateEvent whose kind (field 1) is a value the current enum does not have. */
    private fun EntryAuthor.futureStateEvent(kind: Int): DecodedEntry = create {
        val inner = Buffer().writeByte(0x08).writeByte(kind).readByteString() // kind < 128: one varint byte
        it.copy(unknownFields = Buffer().writeByte(13 shl 3 or 2).writeByte(inner.size).write(inner).readByteString())
    }

    @Test
    fun aCurrentNodeKeepsAndRelaysWhatANewerNodeWrites() = runBlocking<Unit> {
        val (future, current, legacy) = buildGroup(3)
        val newKind = future.author.futureKind()
        val newEvent = future.author.futureStateEvent(99)
        val newCategory = future.author.categoryOverride("com.example.game", CategoryOverrideRules.MAX_CATEGORY + 1)
        future.publish(newKind, newEvent, newCategory)

        val (rf, rc) = syncPair(future, current, versionsA = 2..3, versionsB = 2..2)
        assertEquals(2, assertIs<SessionResult.Completed>(rf).protocolVersion)
        assertEquals(2, assertIs<SessionResult.Completed>(rc).protocolVersion)
        val held = current.store.liveEntries()
        assertTrue(held.containsAll(listOf(newKind, newEvent, newCategory)), "the current node must keep all three")
        assertEquals(EntryKind.UNKNOWN, held.single { it == newKind }.kind)
        assertEquals(StateEvent.Kind.KIND_UNSPECIFIED, held.single { it == newEvent }.body.state_event!!.kind)

        val (rc2, rl) = syncPair(current, legacy, legacyB = true)
        assertIs<SessionResult.Completed>(rc2)
        assertIs<SessionResult.Completed>(rl)
        assertTrue(legacy.store.liveEntries().containsAll(listOf(newKind, newEvent, newCategory)), "relayed unchanged")
        assertEquals(future.store.versionVector()[future.keys.deviceId], legacy.store.versionVector()[future.keys.deviceId])
    }

    @Test
    fun meaningFailuresNeitherBlockTheRelayNorCloseTheGap() = runBlocking<Unit> {
        val (author, relay, receiver) = buildGroup(3)
        val bad = (0 until SyncSession.MAX_INVALID + 2).map { author.author.categoryOverride("com.example.app$it", -1) }
        // A relay whose rules accept them (another app version): the receiver must not block it for them.
        relay.store.applyBatch(bad)
        val (rr, rv) = syncPair(relay, receiver)
        assertIs<SessionResult.Completed>(rr) // not TOO_MANY_INVALID
        assertIs<SessionResult.Completed>(rv)
        assertTrue(receiver.store.liveEntries().none { it in bad }, "skipped entries are not stored")
        // Not accounted: the gap stays for the author to close (the relay is not the author, so no completeAuthor).
        assertFalse(receiver.store.isAccounted(author.keys.deviceId, bad.first().seq), "a skipped seq stays a gap")
        // A direct session with the author (its own log holds them) closes the gap (spec §5 step 5), storing none.
        author.publish(*bad.toTypedArray())
        val (ra, rv2) = syncPair(author, receiver)
        assertIs<SessionResult.Completed>(ra)
        assertIs<SessionResult.Completed>(rv2)
        assertTrue(bad.all { receiver.store.isAccounted(author.keys.deviceId, it.seq) }, "the author closes the gap")
        assertTrue(receiver.store.liveEntries().none { it in bad }, "still not stored")
    }
}
