package app.kinsight.core.keystore

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys

data class SelfTestResult(val passed: Boolean, val detail: String)

/**
 * Proves a key backend works end to end: its signature verifies with the published public key (and only for
 * the signed message), and its X25519 agreement matches a software peer. Never throws.
 */
object KeySelfTest {
    fun run(keys: DeviceKeys): SelfTestResult = try {
        val msg = "kinsight-self-test".encodeToByteArray() + Primitives.randomBytes(16)
        val sig = keys.sign.sign(msg)
        check(sig.size == 64) { "signature has ${sig.size} bytes" }
        check(Ed25519.verify(keys.sign.publicKey, msg, sig)) { "signature does not verify" }
        val other = msg.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(!Ed25519.verify(keys.sign.publicKey, other, sig)) { "signature verifies for another message" }
        val peer = X25519.generate()
        val ours = keys.dh.agree(peer.publicKey)
        check(ours.contentEquals(peer.agree(keys.dh.publicKey))) { "X25519 agreement differs from software peer" }
        SelfTestResult(true, "sign/verify and X25519 agreement ok")
    } catch (e: Exception) {
        SelfTestResult(false, "${e::class.simpleName}: ${e.message}")
    }
}
