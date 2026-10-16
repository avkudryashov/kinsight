package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimitivesTest {
    @Test
    fun sha256Abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Primitives.sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256ConcatenatesParts() {
        assertContentEquals(
            Primitives.sha256("abc".encodeToByteArray()),
            Primitives.sha256("a".encodeToByteArray(), "bc".encodeToByteArray()),
        )
    }

    @Test
    fun hmacRfc4231Case2() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Primitives.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun chachaRoundTripAndTamper() {
        val key = Primitives.randomBytes(32)
        val nonce = ByteArray(12)
        val aad = "ad".encodeToByteArray()
        val ct = Primitives.chachaSeal(key, nonce, aad, "hello".encodeToByteArray())
        assertEquals(5 + 16, ct.size)
        assertEquals("hello", Primitives.chachaOpen(key, nonce, aad, ct).decodeToString())
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertFailsWith<CryptoException> { Primitives.chachaOpen(key, nonce, aad, ct) }
    }

    @Test
    fun ed25519Rfc8032Test1() {
        val kp = Ed25519.fromSeed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes())
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", kp.publicKey.toHex())
        val sig = Ed25519.sign(kp.privateKey, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.toHex(),
        )
        assertTrue(Ed25519.verify(kp.publicKey, ByteArray(0), sig))
        assertFalse(Ed25519.verify(kp.publicKey, byteArrayOf(1), sig))
        assertFalse(Ed25519.verify(kp.publicKey, ByteArray(0), sig.copyOf(10)))
    }

    @Test
    fun x25519Rfc7748PublicKeyAndSymmetry() {
        val alice = X25519.fromPrivate("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes())
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey.toHex())
        val bob = X25519.generate()
        assertContentEquals(X25519.agree(alice.privateKey, bob.publicKey), X25519.agree(bob.privateKey, alice.publicKey))
    }

    @Test
    fun x25519RejectsLowOrderPoint() {
        val alice = X25519.generate()
        assertFailsWith<CryptoException> { X25519.agree(alice.privateKey, ByteArray(32)) }
    }

    @Test
    fun argon2idMatchesTheReferenceVector() {
        // phc-winner-argon2 test vector: Argon2id version 0x13, t = 2, m = 2^16 KiB, p = 1, "password", "somesalt".
        assertEquals(
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            Primitives.argon2id("password".encodeToByteArray(), "somesalt".encodeToByteArray(), memoryKib = 65_536, iterations = 2).toHex(),
        )
    }

    @Test
    fun argon2idDependsOnTheSalt() {
        val a = Primitives.argon2id("2468".encodeToByteArray(), ByteArray(16) { 1 }, memoryKib = 8_192, iterations = 1)
        val b = Primitives.argon2id("2468".encodeToByteArray(), ByteArray(16) { 2 }, memoryKib = 8_192, iterations = 1)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }
}
