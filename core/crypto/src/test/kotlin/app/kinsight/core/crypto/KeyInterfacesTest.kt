package app.kinsight.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A DhKey that never exposes its private half, like an Android Keystore key. */
private class OpaqueDh(private val inner: X25519KeyPair = X25519.generate()) : DhKey {
    override val publicKey: ByteArray get() = inner.publicKey.copyOf()
    override fun agree(peerPublicKey: ByteArray): ByteArray = inner.agree(peerPublicKey)
}

class KeyInterfacesTest {
    @Test
    fun softwareSignerProducesVerifiableSignatures() {
        val signer: Signer = Ed25519.generate()
        val msg = "entry".encodeToByteArray()
        val sig = signer.sign(msg)
        assertEquals(64, sig.size)
        assertTrue(Ed25519.verify(signer.publicKey, msg, sig))
    }

    @Test
    fun softwareDhKeyAgreesWithPeer() {
        val a: DhKey = X25519.generate()
        val b = X25519.generate()
        assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    }

    @Test
    fun dhKeyRejectsBadLengthAndLowOrderPoints() {
        val k: DhKey = X25519.generate()
        assertFailsWith<CryptoException> { k.agree(ByteArray(31)) }
        assertFailsWith<CryptoException> { k.agree(ByteArray(32)) } // u = 0
        assertFailsWith<CryptoException> { k.agree(ByteArray(32).also { it[0] = 1 }) } // u = 1, order 4
    }

    @Test
    fun noiseRunsOnOpaqueStaticKeys() {
        val si = OpaqueDh()
        val sr = OpaqueDh()
        val prologue = "p".encodeToByteArray()
        val i = NoiseHandshake.initiator(si, prologue)
        val r = NoiseHandshake.responder(sr, prologue)
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        assertContentEquals(sr.publicKey, i.remoteStaticPublicKey)
        assertContentEquals(si.publicKey, r.remoteStaticPublicKey)
        val ti = i.split()
        val tr = r.split()
        assertEquals("ok", tr.decrypt(ti.encrypt("ok".encodeToByteArray())).decodeToString())
    }
}
