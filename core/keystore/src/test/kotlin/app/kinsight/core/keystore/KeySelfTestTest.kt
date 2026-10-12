package app.kinsight.core.keystore

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.Signer
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeySelfTestTest {
    private val goodSign = Ed25519.generate()
    private val goodDh = X25519.generate()

    @Test
    fun passesForSoftwareKeys() {
        val r = KeySelfTest.run(DeviceKeys.generate())
        assertTrue(r.passed, r.detail)
    }

    @Test
    fun failsForSignatureThatDoesNotVerify() {
        val bad = object : Signer {
            override val publicKey = goodSign.publicKey
            override fun sign(message: ByteArray) = ByteArray(64)
        }
        val r = KeySelfTest.run(DeviceKeys(bad, goodDh))
        assertFalse(r.passed)
        assertTrue("signature" in r.detail, r.detail)
    }

    @Test
    fun failsForMismatchedAgreement() {
        val bad = object : DhKey {
            override val publicKey = goodDh.publicKey
            override fun agree(peerPublicKey: ByteArray) = Primitives.randomBytes(32)
        }
        val r = KeySelfTest.run(DeviceKeys(goodSign, bad))
        assertFalse(r.passed)
        assertTrue("agreement" in r.detail, r.detail)
    }

    @Test
    fun propagatesCancellation() {
        val cancelled = CancellationException("scope cancelled")
        val cancelling = object : DhKey {
            override val publicKey = goodDh.publicKey
            override fun agree(peerPublicKey: ByteArray): ByteArray = throw cancelled
        }
        assertSame(cancelled, assertFailsWith<CancellationException> { KeySelfTest.run(DeviceKeys(goodSign, cancelling)) })
    }

    @Test
    fun reportsBackendExceptionsInsteadOfThrowing() {
        val throwing = object : Signer {
            override val publicKey = goodSign.publicKey
            override fun sign(message: ByteArray): ByteArray = throw IllegalStateException("TEE gone")
        }
        val r = KeySelfTest.run(DeviceKeys(throwing, goodDh))
        assertFalse(r.passed)
        assertTrue("TEE gone" in r.detail, r.detail)
    }
}
