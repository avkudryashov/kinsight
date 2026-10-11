package app.kinsight.core.sync.pairing

import app.kinsight.core.crypto.Primitives

/**
 * Short authentication string of one pairing channel (audit S-M1): six digits both phones derive from the Noise
 * handshake hash. The admin adds the guest only when its screen shows the same digits as the phone in front of it, so
 * someone who saw the QR and connected first cannot pass as the invited phone. Local only: nothing new on the wire.
 */
object PairingSas {
    const val DIGITS = 6
    private val LABEL = "kinsight-pair-sas-v1".encodeToByteArray()
    private const val MODULUS = 1_000_000L

    fun code(handshakeHash: ByteArray): String {
        require(handshakeHash.size == 32) { "a handshake hash is 32 bytes" }
        val mac = Primitives.hmacSha256(handshakeHash, LABEL)
        var n = 0L
        for (i in 0 until 4) n = (n shl 8) or (mac[i].toLong() and 0xff)
        return (n % MODULUS).toString().padStart(DIGITS, '0')
    }

    /** «123 456»: easier to read aloud and compare. */
    fun display(code: String): String = if (code.length == DIGITS) code.substring(0, 3) + " " + code.substring(3) else code
}
