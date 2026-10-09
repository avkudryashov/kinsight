package app.kinsight.core.crypto

class HpkeSealed(val enc: ByteArray, val ciphertext: ByteArray)

/**
 * RFC 9180 base mode with DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and ChaCha20Poly1305, single message
 * (sequence number 0). Opening needs only [DhKey.agree], so the recipient key may be non-exportable.
 */
object Hpke {
    private const val KEM_ID = 0x0020
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0003
    private const val N_SECRET = 32
    private const val N_KEY = 32
    private const val N_NONCE = 12
    private const val N_ENC = 32
    private val VERSION = "HPKE-v1".encodeToByteArray()
    private val KEM_SUITE = "KEM".encodeToByteArray() + i2osp(KEM_ID, 2)
    private val HPKE_SUITE = "HPKE".encodeToByteArray() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)
    private val EMPTY = ByteArray(0)

    fun seal(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        ephemeral: X25519KeyPair = X25519.generate(),
    ): HpkeSealed {
        val enc = ephemeral.publicKey.copyOf()
        val dh = ephemeral.agree(recipientPublicKey)
        val (key, nonce) = keySchedule(sharedSecret(dh, enc, recipientPublicKey), info)
        return HpkeSealed(enc = enc, ciphertext = Primitives.chachaSeal(key, nonce, aad, plaintext))
    }

    fun open(recipient: DhKey, info: ByteArray, aad: ByteArray, sealed: HpkeSealed): ByteArray {
        if (sealed.enc.size != N_ENC) throw CryptoException("bad HPKE enc length")
        val dh = recipient.agree(sealed.enc)
        val (key, nonce) = keySchedule(sharedSecret(dh, sealed.enc, recipient.publicKey), info)
        return Primitives.chachaOpen(key, nonce, aad, sealed.ciphertext)
    }

    /** DHKEM ExtractAndExpand (RFC 9180 §4.1). */
    internal fun sharedSecret(dh: ByteArray, enc: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val eaePrk = labeledExtract(KEM_SUITE, EMPTY, "eae_prk", dh)
        return labeledExpand(KEM_SUITE, eaePrk, "shared_secret", enc + recipientPublicKey, N_SECRET)
    }

    /** KeyScheduleS/R for mode_base without PSK (RFC 9180 §5.1): returns (key, base_nonce). */
    internal fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(HPKE_SUITE, EMPTY, "psk_id_hash", EMPTY)
        val infoHash = labeledExtract(HPKE_SUITE, EMPTY, "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", EMPTY)
        return labeledExpand(HPKE_SUITE, secret, "key", context, N_KEY) to
            labeledExpand(HPKE_SUITE, secret, "base_nonce", context, N_NONCE)
    }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        // HKDF-Extract: an absent salt is HashLen zero bytes (RFC 5869 §2.2).
        Primitives.hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, VERSION, suite, label.encodeToByteArray(), ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        require(length in 1..32) { "single-block HKDF-Expand only" }
        val labeledInfo = i2osp(length, 2) + VERSION + suite + label.encodeToByteArray() + info
        return Primitives.hmacSha256(prk, labeledInfo, byteArrayOf(1)).copyOf(length)
    }

    private fun i2osp(value: Int, length: Int) = ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
}
