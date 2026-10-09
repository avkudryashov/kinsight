package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HpkeTest {
    private val info = "kinsight-discovery-key".encodeToByteArray()

    @Test
    fun sealOpenRoundTrip() {
        val r = X25519.generate()
        val sealed = Hpke.seal(r.publicKey, info, byteArrayOf(7), "secret".encodeToByteArray())
        assertEquals(32, sealed.enc.size)
        assertEquals("secret", Hpke.open(r, info, byteArrayOf(7), sealed).decodeToString())
    }

    @Test
    fun wrongRecipientFails() {
        val sealed = Hpke.seal(X25519.generate().publicKey, info, ByteArray(0), byteArrayOf(1))
        assertFailsWith<CryptoException> { Hpke.open(X25519.generate(), info, ByteArray(0), sealed) }
    }

    @Test
    fun wrongAadFails() {
        val r = X25519.generate()
        val sealed = Hpke.seal(r.publicKey, info, byteArrayOf(1), byteArrayOf(1))
        assertFailsWith<CryptoException> { Hpke.open(r, info, byteArrayOf(2), sealed) }
    }
}
