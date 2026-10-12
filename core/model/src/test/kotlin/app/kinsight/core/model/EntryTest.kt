package app.kinsight.core.model

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Signer
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.proto.CategoryOverride
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.model.proto.UsageSummary
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "Mom", clock = { 1_000L })
    private val author = EntryAuthor(genesis.groupId, keys, lastSeq = 1, clock = { 2_000L })

    @Test
    fun entriesSignedThroughOpaqueSignerVerify() {
        val inner = Ed25519.generate()
        val opaque = object : Signer {
            override val publicKey: ByteArray = inner.publicKey.copyOf()
            override fun sign(message: ByteArray): ByteArray = inner.sign(message)
        }
        val k = DeviceKeys(opaque, X25519.generate())
        val g = GroupGenesis.create(k, "Opaque")
        assertTrue(g.entry.verifySignature(k.sign.publicKey))
        assertEquals(k.deviceId, g.entry.author)
        assertNotNull(Membership.compute(g.groupId, listOf(g.entry)))
    }

    @Test
    fun brokenSignerFailsWithoutConsumingSeq() {
        val inner = Ed25519.generate()
        val broken = object : Signer {
            override val publicKey: ByteArray = inner.publicKey.copyOf()
            override fun sign(message: ByteArray): ByteArray = ByteArray(64)
        }
        val a = EntryAuthor(genesis.groupId, DeviceKeys(broken, X25519.generate()), lastSeq = 4)
        assertFailsWith<IllegalStateException> {
            a.create { it.copy(heartbeat = Heartbeat(app_version = "1", protocol_version = 1)) }
        }
        assertEquals(4L, a.lastSeq)
    }

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
    fun categoryOverrideRoundTripsAndSupersedesPerAuthorAndPackage() {
        val e = author.create { it.copy(category_override = CategoryOverride(package_name = "com.example.game", category = 1)) }
        val again = DecodedEntry.decode(e.bytes)
        assertEquals(e, again)
        assertEquals(EntryKind.CATEGORY_OVERRIDE, again.kind)
        assertEquals("com.example.game", again.body.category_override!!.package_name)
        assertEquals(1, again.body.category_override!!.category)
        assertEquals("${keys.deviceId.hex}/cat/com.example.game", again.supersedeKey)
        assertNull(again.usageDate)
        assertFalse(again.isMembershipRelevant)
        assertEquals(e.bytes.size - 64 - 4, again.bodySize) // envelope: two tags, two lengths, 64-byte signature
        // A cleared override (category 0) keeps the same key, so it replaces the earlier one.
        val cleared = author.create { it.copy(category_override = CategoryOverride(package_name = "com.example.game", category = 0)) }
        assertEquals(again.supersedeKey, cleared.supersedeKey)
        val other = author.create { it.copy(category_override = CategoryOverride(package_name = "com.example.other", category = 2)) }
        assertEquals("${keys.deviceId.hex}/cat/com.example.other", other.supersedeKey)
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
    fun lamportExceedsOwnPreviousAndObserved() {
        assertEquals(1L, genesis.entry.lamport)
        var observed = 0L
        val a = EntryAuthor(genesis.groupId, keys, lastSeq = 1, lastLamport = 1, observedLamport = { observed })
        assertEquals(2L, a.create { it }.lamport)
        observed = 10
        assertEquals(11L, a.create { it }.lamport)
        observed = 3 // observing less never moves the author's clock back
        assertEquals(12L, a.create { it }.lamport)
        assertEquals(12L, a.lastLamport)
    }

    @Test
    fun lamportSaturatesBelowTheValidityCap() {
        val a = EntryAuthor(genesis.groupId, keys, lastSeq = 1, observedLamport = { Long.MAX_VALUE - 1 })
        assertEquals(MembershipLimits.MAX_LAMPORT, a.create { it }.lamport)
        assertEquals(MembershipLimits.MAX_LAMPORT, a.create { it }.lamport)
    }

    @Test
    fun authorRefusesToChangeHeader() {
        assertFailsWith<IllegalArgumentException> {
            author.create { it.copy(seq = 99, membership_op = MembershipOp()) }
        }
    }
}
