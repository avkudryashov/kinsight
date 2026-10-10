package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.DeviceRole
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
}
