package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.MIN_PROTOCOL_VERSION
import app.kinsight.core.sync.PROTOCOL_VERSION
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InviteTest {
    private val admin = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(admin, "Mom")
    private val invite = Invite.create(genesis.groupId, admin, DeviceRole.CHILD, "Mom")

    @Test
    fun roundTripThroughTheUri() {
        val uri = invite.toUri()
        assertTrue(uri.startsWith(Invite.PREFIX))
        val back = Invite.parse(uri)
        assertEquals(invite.groupId, back.groupId)
        assertContentEquals(invite.adminSignKey, back.adminSignKey)
        assertContentEquals(invite.adminDhKey, back.adminDhKey)
        assertContentEquals(invite.token, back.token)
        assertEquals(DeviceRole.CHILD, back.role)
        assertEquals("Mom", back.adminName)
        assertEquals(admin.deviceId, back.adminId)
    }

    @Test
    fun rejectsForeignOrDamagedText() {
        val good = invite.encode()
        val enc = Base64.getUrlEncoder().withoutPadding()
        val bad = listOf(
            "https://example.com/pair",
            Invite.PREFIX + "%%%",
            Invite.PREFIX + enc.encodeToString(good.copyOf(good.size - 1)),
            Invite.PREFIX + enc.encodeToString(good + byteArrayOf(0)),
            Invite.PREFIX + enc.encodeToString(good.copyOf().also { it[0] = 2 }),
            Invite.PREFIX + enc.encodeToString(good.copyOf().also { it[1 + 2 + 16 + 32 + 32 + 16] = 0 }), // role unspecified
            Invite.PREFIX + enc.encodeToString(good.copyOf().also { it[1 + 2 + 16 + 32 + 32 + 16] = 9 }),
        )
        for (text in bad) assertFailsWith<InvalidInviteException>(text) { Invite.parse(text) }
    }

    @Test
    fun truncatesTheNameOnACodePointBoundary() {
        val name = "Мама" + "😀".repeat(20) // 8 bytes + 80 bytes
        val cut = Invite.truncateName(name)
        assertTrue(cut.encodeToByteArray().size <= Invite.MAX_NAME_BYTES)
        assertTrue(name.startsWith(cut))
        assertFalse(cut.last().isHighSurrogate())
        assertEquals(cut, Invite.parse(Invite.create(genesis.groupId, admin, DeviceRole.PARENT, name).toUri()).adminName)
    }

    @Test
    fun advertTagDependsOnTheTokenOnly() {
        val other = Invite(invite.protocolVersion, invite.groupId, invite.adminSignKey, invite.adminDhKey, Primitives.randomBytes(16), invite.role, invite.adminName)
        assertEquals(Invite.ADVERT_TAG_BYTES, invite.advertTag.size)
        assertContentEquals(invite.advertTag, Invite.parse(invite.toUri()).advertTag)
        assertFalse(invite.advertTag.contentEquals(other.advertTag))
    }

    private fun withName(name: ByteArray): String {
        val head = invite.encode().copyOf(1 + 2 + 16 + 32 + 32 + 16 + 1)
        return Invite.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(head + byteArrayOf(name.size.toByte()) + name)
    }

    @Test
    fun rejectsOverlongText() {
        val padded = invite.toUri() + "\n".repeat(Invite.MAX_TEXT_CHARS)
        assertFailsWith<InvalidInviteException> { Invite.parse(padded) }
    }

    @Test
    fun otherVersionsAreUnsupported() {
        assertFailsWith<UnsupportedInviteVersionException> { Invite.parse("kinsight://pair/2/AAAA") }
        val newer = Invite(PROTOCOL_VERSION + 1, invite.groupId, invite.adminSignKey, invite.adminDhKey, invite.token, invite.role, invite.adminName)
        assertFailsWith<UnsupportedInviteVersionException> { Invite.parse(newer.toUri()) }
    }

    @Test
    fun rejectsBlankOrUnsafeAdminNames() {
        assertEquals("Mom", Invite.parse(withName("Mom".encodeToByteArray())).adminName)
        for (name in listOf("", "   ", "Mo\u202Em", "Mo\u2066m", "Mo\u0007m", "Mo\u0085m", "Mo\u007Fm")) {
            assertFailsWith<InvalidInviteException>(name) { Invite.parse(withName(name.encodeToByteArray())) }
        }
        assertFailsWith<IllegalArgumentException> {
            Invite(invite.protocolVersion, invite.groupId, invite.adminSignKey, invite.adminDhKey, invite.token, invite.role, " ")
        }
    }

    @Test
    fun createStripsUnsafeCharacters() {
        assertEquals("Mom", Invite.create(genesis.groupId, admin, DeviceRole.CHILD, " Mo\u202Em\u0000\u2069 ").adminName)
    }

    @Test
    fun currentInvitesKeepThe072Layout() {
        // min = max = 2: no range tail, so a 0.7.2 phone (strict about trailing bytes) still parses our invites.
        assertEquals(MIN_PROTOCOL_VERSION, invite.minProtocolVersion)
        assertEquals(PROTOCOL_VERSION, invite.protocolVersion)
        assertEquals(1 + 2 + 16 + 32 + 32 + 16 + 1 + 1 + "Mom".encodeToByteArray().size, invite.encode().size)
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytes(from: Int, n: Int) = ByteArray(n) { (from + it).toByte() }

    private val golden = Invite(
        2, app.kinsight.core.model.GroupId.fromBytes(bytes(0x01, 16)), bytes(0x20, 32), bytes(0x40, 32), bytes(0x60, 16),
        DeviceRole.CHILD, "Mom",
    )

    /**
     * The exact 0.7.2 invite bytes for a 2..2 admin: format 01, protocol 0002, groupId, Ed25519 key, X25519 key, token,
     * role 02 (CHILD), name length 03, "Mom", and no tail. Any change here breaks 0.7.2 joiners.
     */
    private val goldenHex = "01" + "0002" +
        "0102030405060708090a0b0c0d0e0f10" +
        "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
        "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" +
        "606162636465666768696a6b6c6d6e6f" +
        "02" + "03" + "4d6f6d"

    @Test
    fun aTwoToTwoInviteIsTheGolden072Layout() {
        assertContentEquals(hex(goldenHex), golden.encode())
        val back = Invite.decode(hex(goldenHex))
        assertEquals(2, back.protocolVersion)
        assertEquals(2, back.minProtocolVersion)
        assertEquals("Mom", back.adminName)
        assertEquals(Legacy072.Outcome.OK, Legacy072.decode(golden.encode()))
    }

    /** The 0.7.2 parser, verbatim in what it checks: exactly protocol 2 in the slot, and no byte after the name. */
    private object Legacy072 {
        enum class Outcome { OK, UPDATE, INVALID }

        fun decode(b: ByteArray): Outcome {
            val input = java.io.DataInputStream(b.inputStream())
            if (input.readUnsignedByte() != 1) return Outcome.INVALID
            if (input.readUnsignedShort() != 2) return Outcome.UPDATE // UnsupportedInviteVersionException: «update the app»
            input.skipNBytes(16L + 32 + 32 + 16 + 1)
            input.skipNBytes(input.readUnsignedByte().toLong())
            return if (input.read() != -1) Outcome.INVALID else Outcome.OK // "trailing bytes"
        }
    }

    @Test
    fun aRangedInviteTells072JoinersToUpdate() {
        // Any tail breaks a 0.7.2 parser (strict about trailing bytes), whichever version sits in the slot. With the
        // highest version in the slot it fails on the version first and says «update»; the lowest in the slot would
        // pass that check and then fail as an invalid invite. So the slot keeps the highest (spec §6.4).
        val wider = Invite(3, golden.groupId, golden.adminSignKey, golden.adminDhKey, golden.token, golden.role, golden.adminName, minProtocolVersion = 2)
        assertEquals(Legacy072.Outcome.UPDATE, Legacy072.decode(wider.encode()))
        val swapped = wider.encode().also { it[1] = 0; it[2] = 2; it[it.size - 2] = 0; it[it.size - 1] = 3 }
        assertEquals(Legacy072.Outcome.INVALID, Legacy072.decode(swapped))
    }

    @Test
    fun anOverlappingRangeIsAccepted() {
        val wider = Invite(PROTOCOL_VERSION + 1, invite.groupId, invite.adminSignKey, invite.adminDhKey, invite.token, invite.role, invite.adminName, minProtocolVersion = PROTOCOL_VERSION)
        val back = Invite.parse(wider.toUri())
        assertEquals(PROTOCOL_VERSION, back.minProtocolVersion)
        assertEquals(PROTOCOL_VERSION + 1, back.protocolVersion)
        assertEquals(invite.encode().size + 2, wider.encode().size)
    }

    @Test
    fun aDisjointRangeIsUnsupported() {
        val newer = Invite(PROTOCOL_VERSION + 2, invite.groupId, invite.adminSignKey, invite.adminDhKey, invite.token, invite.role, invite.adminName, minProtocolVersion = PROTOCOL_VERSION + 1)
        assertFailsWith<UnsupportedInviteVersionException> { Invite.parse(newer.toUri()) }
    }

    @Test
    fun aMinimumAboveTheMaximumIsInvalid() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val tail = byteArrayOf(0, (PROTOCOL_VERSION + 1).toByte()) // min 3 over max 2
        assertFailsWith<InvalidInviteException> { Invite.parse(Invite.PREFIX + enc.encodeToString(invite.encode() + tail)) }
        assertFailsWith<InvalidInviteException> { Invite.parse(Invite.PREFIX + enc.encodeToString(invite.encode() + tail + byteArrayOf(0))) }
    }
}
