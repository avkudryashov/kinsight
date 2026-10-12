package app.kinsight.core.keystore

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.X25519
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.KeyAgreement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpkiTest {
    private val msg = "spki".encodeToByteArray()

    @Test
    fun ed25519RawKeyVerifiesJdkSignature() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = Spki.ed25519Raw(kp.public.encoded)
        assertEquals(32, raw.size)
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() }
        assertTrue(Ed25519.verify(raw, msg, sig))
    }

    @Test
    fun x25519RawKeyAndReimportAgreeWithJdk() {
        val jdk = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val ours = X25519.generate()
        val jdkSecret = KeyAgreement.getInstance("XDH").run {
            init(jdk.private)
            doPhase(Spki.x25519PublicKey(ours.publicKey), true)
            generateSecret()
        }
        assertContentEquals(ours.agree(Spki.x25519Raw(jdk.public.encoded)), jdkSecret)
    }

    @Test
    fun rejectsWrongAlgorithmAndLength() {
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        val x = KeyPairGenerator.getInstance("X25519").generateKeyPair().public.encoded
        assertFailsWith<IllegalArgumentException> { Spki.x25519Raw(ed) }
        assertFailsWith<IllegalArgumentException> { Spki.ed25519Raw(x) }
        assertFailsWith<IllegalArgumentException> { Spki.ed25519Raw(ed.copyOf(43)) }
        assertFailsWith<IllegalArgumentException> { Spki.x25519PublicKey(ByteArray(31)) }
    }
}
