package app.kinsight.debug

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.transport.ble.GroupConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DebugSeedTest {
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "pixel")
    private val config = GroupConfig(genesis.groupId, Primitives.randomBytes(32))

    @Test
    fun roundTrip() {
        val seed = DebugSeed(config, listOf(genesis.entry.bytes, byteArrayOf(1, 2, 3)))
        val back = DebugSeed.decode(seed.encode())
        assertEquals(config.groupId, back.config.groupId)
        assertContentEquals(config.discoveryKey, back.config.discoveryKey)
        assertEquals(2, back.membership.size)
        assertContentEquals(genesis.entry.bytes, back.membership[0])
        assertContentEquals(byteArrayOf(1, 2, 3), back.membership[1])
    }

    @Test
    fun rejectsTrailingBytesAndUnknownVersion() {
        val hex = DebugSeed(config, emptyList()).encode()
        assertFailsWith<IllegalArgumentException> { DebugSeed.decode(hex + "00") }
        assertFailsWith<IllegalArgumentException> { DebugSeed.decode("02" + hex.drop(2)) }
    }

    @Test
    fun rejectsTruncatedSeed() {
        val hex = DebugSeed(config, listOf(genesis.entry.bytes)).encode()
        assertFailsWith<IllegalArgumentException> { DebugSeed.decode(hex.dropLast(2)) }
        assertFailsWith<IllegalArgumentException> { DebugSeed.decode("") }
    }

    @Test
    fun memberSpecParsesWhatIdentityPrints() {
        val other = DeviceKeys.generate()
        val m = MemberSpec.parse("${other.sign.publicKey.toHex()}:${other.dh.publicKey.toHex()}:galaxy s23")
        assertContentEquals(other.sign.publicKey, m.sign)
        assertContentEquals(other.dh.publicKey, m.dh)
        assertEquals("galaxy s23", m.name)
        assertFailsWith<IllegalArgumentException> { MemberSpec.parse("abcd:ef:x") }
        assertFailsWith<IllegalArgumentException> { MemberSpec.parse("only-one-field") }
    }
}
