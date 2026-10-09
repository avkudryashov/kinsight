package app.kinsight.core.crypto

import org.bouncycastle.crypto.hpke.HPKE
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    // RFC 9180 Appendix A.2.1: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20Poly1305, base mode, sequence 0.
    private val rfcInfo = "4f6465206f6e2061204772656369616e2055726e".hexToBytes()
    private val skEm = "f4ec9b33b792c372c1d2c2063507b684ef925b8c75a42dbcbf57d63ccd381600".hexToBytes()
    private val pkEm = "1afa08d3dec047a643885163f1180476fa7ddb54c6a8029ea33f95796bf2ac4a".hexToBytes()
    private val skRm = "8057991eef8f1f1af18f4a9491d16a1ce333f695d4db8e38da75975c4478e0fb".hexToBytes()
    private val pkRm = "4310ee97d88cc1f088a5576c77ab0cf5c3ac797f3d95139c6c84b5429c59662a".hexToBytes()
    private val rfcPt = "4265617574792069732074727574682c20747275746820626561757479".hexToBytes()
    private val rfcAad = "436f756e742d30".hexToBytes()
    private val rfcCt = (
        "1c5250d8034ec2b784ba2cfd69dbdb8af406cfe3ff938e131f0def8c8b60b4db" +
            "21993c62ce81883d2dd1b51a28"
        ).hexToBytes()

    private fun bc() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)

    @Test
    fun rfc9180KeyScheduleMatches() {
        val ss = Hpke.sharedSecret(X25519.agree(skEm, pkRm), pkEm, pkRm)
        assertEquals("0bbe78490412b4bbea4812666f7916932b828bba79942424abb65244930d69a7", ss.toHex())
        val (key, nonce) = Hpke.keySchedule(ss, rfcInfo)
        assertEquals("ad2744de8e17f4ebba575b3f5f5a8fa1f69c2a07f6e7500bc60ca6e3e3ec1c91", key.toHex())
        assertEquals("5c4d98150661b848853b547f", nonce.toHex())
    }

    @Test
    fun rfc9180SealMatches() {
        val sealed = Hpke.seal(pkRm, rfcInfo, rfcAad, rfcPt, ephemeral = X25519.fromPrivate(skEm))
        assertEquals(pkEm.toHex(), sealed.enc.toHex())
        assertEquals(rfcCt.toHex(), sealed.ciphertext.toHex())
    }

    @Test
    fun rfc9180OpenMatches() {
        assertContentEquals(rfcPt, Hpke.open(X25519.fromPrivate(skRm), rfcInfo, rfcAad, HpkeSealed(pkEm, rfcCt)))
    }

    @Test
    fun opensBouncyCastleSealThroughOpaqueKey() {
        val r = X25519.generate()
        val opaque = object : DhKey {
            override val publicKey: ByteArray = r.publicKey.copyOf()
            override fun agree(peerPublicKey: ByteArray): ByteArray = r.agree(peerPublicKey)
        }
        val hpke = bc()
        val out = hpke.seal(hpke.deserializePublicKey(r.publicKey), info, byteArrayOf(9), "bc".encodeToByteArray(), null, null, null)
        val opened = Hpke.open(opaque, info, byteArrayOf(9), HpkeSealed(enc = out[1], ciphertext = out[0]))
        assertEquals("bc", opened.decodeToString())
    }

    @Test
    fun bouncyCastleOpensOurSeal() {
        val r = X25519.generate()
        val sealed = Hpke.seal(r.publicKey, info, byteArrayOf(3), "ours".encodeToByteArray())
        val hpke = bc()
        val pt = hpke.open(sealed.enc, hpke.deserializePrivateKey(r.privateKey, r.publicKey), info, byteArrayOf(3), sealed.ciphertext, null, null, null)
        assertEquals("ours", pt.decodeToString())
    }

    @Test
    fun malformedEncFails() {
        val r = X25519.generate()
        val sealed = Hpke.seal(r.publicKey, info, ByteArray(0), byteArrayOf(1))
        assertFailsWith<CryptoException> { Hpke.open(r, info, ByteArray(0), HpkeSealed(sealed.enc.copyOf(31), sealed.ciphertext)) }
        assertFailsWith<CryptoException> { Hpke.open(r, info, ByteArray(0), HpkeSealed(ByteArray(32), sealed.ciphertext)) }
    }
}
