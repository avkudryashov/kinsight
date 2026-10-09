package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HexTest {
    @Test
    fun roundTrip() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        assertEquals("00017f80ff", bytes.toHex())
        assertContentEquals(bytes, "00017f80ff".hexToBytes())
    }

    @Test
    fun rejectsOddLength() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
    }
}
