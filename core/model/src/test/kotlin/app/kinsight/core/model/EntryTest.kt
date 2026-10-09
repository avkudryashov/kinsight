package app.kinsight.core.model

import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "Mom", clock = { 1_000L })
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1, clock = { 2_000L })

    @Test
    fun genesisIsSelfConsistent() {
        val create = genesis.entry.body.membership_op!!.create!!
        assertEquals(1L, genesis.entry.seq)
        assertEquals(keys.deviceId, genesis.entry.author)
        assertEquals(GroupId.derive(keys.sign.publicKey, create.salt.toByteArray()), genesis.groupId)
        assertTrue(genesis.entry.isMembershipRelevant)
        assertTrue(genesis.entry.verifySignature(keys.sign.publicKey))
    }

    @Test
    fun authorIncrementsSeqAndSigns() {
        val e = author.create { it.copy(heartbeat = Heartbeat(app_version = "1.0", protocol_version = 1)) }
        assertEquals(2L, e.seq)
        assertEquals(2L, author.lastSeq)
        assertEquals(EntryKind.HEARTBEAT, e.kind)
        assertEquals("${keys.deviceId.hex}/hb", e.supersedeKey)
        assertTrue(e.verifySignature(keys.sign.publicKey))
        assertFalse(e.verifySignature(DeviceKeys.generate().sign.publicKey))
    }

    @Test
    fun decodeRoundTripKeepsExactBytes() {
        val e = author.create { it.copy(usage_summary = UsageSummary(local_date = "2026-10-09", zone_id = "Europe/Madrid", total_ms = 5)) }
        val again = DecodedEntry.decode(e.bytes)
        assertEquals(e, again)
        assertEquals(LocalDate.of(2026, 10, 9), again.usageDate)
        assertEquals("${keys.deviceId.hex}/sum/2026-10-09", again.supersedeKey)
    }

    @Test
    fun tamperedBytesFailSignature() {
        val e = author.create { it.copy(heartbeat = Heartbeat(app_version = "1.0")) }
        val bad = e.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(DecodedEntry.decode(bad).verifySignature(keys.sign.publicKey))
    }

    @Test
    fun garbageIsFormatError() {
        assertFailsWith<EntryFormatException> { DecodedEntry.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun unknownPayloadIsUnknownKind() {
        val e = author.create { it }
        assertEquals(EntryKind.UNKNOWN, e.kind)
        assertNull(e.supersedeKey)
    }

    @Test
    fun leftGroupIsMembershipRelevantButOtherEventsAreNot() {
        val left = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.LEFT_GROUP)) }
        val paused = author.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.SHARING_PAUSED)) }
        assertTrue(left.isMembershipRelevant)
        assertFalse(paused.isMembershipRelevant)
    }

    @Test
    fun authorRefusesToChangeHeader() {
        assertFailsWith<IllegalArgumentException> {
            author.create { it.copy(seq = 99, membership_op = MembershipOp()) }
        }
    }
}
