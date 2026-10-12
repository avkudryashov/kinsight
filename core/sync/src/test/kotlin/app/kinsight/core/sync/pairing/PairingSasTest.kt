package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingSasTest {
    @Test
    fun knownVectors() {
        // python3: hmac.new(key, b'kinsight-pair-sas-v1', sha256).digest()[:4] as big-endian unsigned % 10**6
        assertEquals("170514", PairingSas.code(ByteArray(32) { it.toByte() }))
        assertEquals("273964", PairingSas.code(ByteArray(32)))
    }

    @Test
    fun alwaysSixDigits() {
        repeat(500) { i ->
            val c = PairingSas.code(Primitives.sha256(byteArrayOf((i shr 8).toByte(), i.toByte())))
            assertEquals(PairingSas.DIGITS, c.length)
            assertTrue(c.all(Char::isDigit), c)
        }
    }

    @Test
    fun differentHandshakesGiveDifferentCodes() {
        val codes = (0 until 1000).map { PairingSas.code(Primitives.sha256(byteArrayOf((it shr 8).toByte(), it.toByte()))) }.toSet()
        assertTrue(codes.size >= 995, "${codes.size} distinct of 1000") // about 0.5 collisions expected among 10^6 codes
    }

    @Test
    fun displayGroupsByThree() {
        assertEquals("012 345", PairingSas.display("012345"))
    }

    @Test
    fun onlyAHandshakeHashIsAccepted() {
        assertFailsWith<IllegalArgumentException> { PairingSas.code(ByteArray(16)) }
    }
}
