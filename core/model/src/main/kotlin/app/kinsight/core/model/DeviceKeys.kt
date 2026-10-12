package app.kinsight.core.model

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Signer
import app.kinsight.core.crypto.X25519

/** The device identity: an Ed25519 signer and an X25519 static key, software or Android Keystore backed. */
class DeviceKeys(val sign: Signer, val dh: DhKey) {
    val deviceId: DeviceId = DeviceId.fromSignPublicKey(sign.publicKey)

    companion object {
        /** Fresh software keys (tests and the software key backend). */
        fun generate() = DeviceKeys(Ed25519.generate(), X25519.generate())
    }
}
