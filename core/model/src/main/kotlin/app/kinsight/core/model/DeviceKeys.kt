package app.kinsight.core.model

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Ed25519KeyPair
import app.kinsight.core.crypto.X25519
import app.kinsight.core.crypto.X25519KeyPair

class DeviceKeys(val sign: Ed25519KeyPair, val dh: X25519KeyPair) {
    val deviceId: DeviceId = DeviceId.fromSignPublicKey(sign.publicKey)

    companion object {
        fun generate() = DeviceKeys(Ed25519.generate(), X25519.generate())
    }
}
