package app.kinsight.core.crypto

import java.io.ByteArrayOutputStream

private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
private const val DH_LEN = 32
private const val TAG_LEN = 16
private val EMPTY = ByteArray(0)

internal class CipherState(private var k: ByteArray? = null) {
    private var n: Long = 0

    fun initializeKey(key: ByteArray) {
        k = key
        n = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext
        return Primitives.chachaSeal(key, nonce(), ad, plaintext).also { n++ }
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext
        return Primitives.chachaOpen(key, nonce(), ad, ciphertext).also { n++ }
    }

    private fun nonce(): ByteArray {
        if (n < 0) throw CryptoException("nonce exhausted")
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte()
        return out
    }
}

internal fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = Primitives.hmacSha256(chainingKey, ikm)
    val out1 = Primitives.hmacSha256(temp, byteArrayOf(1))
    val out2 = Primitives.hmacSha256(temp, out1, byteArrayOf(2))
    return out1 to out2
}

internal class SymmetricState(prologue: ByteArray) {
    var h: ByteArray
    private var ck: ByteArray
    private val cipher = CipherState()

    init {
        val name = PROTOCOL_NAME.encodeToByteArray()
        check(name.size == 32)
        h = name.copyOf()
        ck = name.copyOf()
        mixHash(prologue)
    }

    fun mixHash(data: ByteArray) {
        h = Primitives.sha256(h, data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, ikm)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ck, EMPTY)
        return CipherState(k1) to CipherState(k2)
    }
}

class NoiseHandshake private constructor(
    private val initiator: Boolean,
    private val s: DhKey,
    prologue: ByteArray,
    private val newEphemeral: () -> X25519KeyPair,
) {
    private val ss = SymmetricState(prologue)
    private var e: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var step = 0

    var remoteStaticPublicKey: ByteArray? = null
        private set

    val isComplete: Boolean get() = step == 3
    val handshakeHash: ByteArray get() = ss.h.copyOf()

    private fun isMyTurn() = (step % 2 == 0) == initiator

    fun writeMessage(payload: ByteArray = EMPTY): ByteArray {
        check(!isComplete) { "handshake already complete" }
        check(isMyTurn()) { "not our turn to write" }
        val out = ByteArrayOutputStream()
        when (step) {
            0 -> {
                val eph = newEphemeral().also { e = it }
                out.write(eph.publicKey)
                ss.mixHash(eph.publicKey)
            }
            1 -> {
                val eph = newEphemeral().also { e = it }
                out.write(eph.publicKey)
                ss.mixHash(eph.publicKey)
                ss.mixKey(eph.agree(re!!)) // ee
                out.write(ss.encryptAndHash(s.publicKey)) // s
                ss.mixKey(s.agree(re!!)) // es
            }
            2 -> {
                out.write(ss.encryptAndHash(s.publicKey)) // s
                ss.mixKey(s.agree(re!!)) // se
            }
        }
        out.write(ss.encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!isComplete) { "handshake already complete" }
        check(!isMyTurn()) { "not our turn to read" }
        var off = 0
        fun take(n: Int): ByteArray {
            if (message.size - off < n) throw CryptoException("handshake message too short")
            return message.copyOfRange(off, off + n).also { off += n }
        }
        when (step) {
            0 -> {
                re = take(DH_LEN).also { ss.mixHash(it) }
            }
            1 -> {
                re = take(DH_LEN).also { ss.mixHash(it) }
                ss.mixKey(e!!.agree(re!!)) // ee
                val rs = ss.decryptAndHash(take(DH_LEN + TAG_LEN)) // s
                remoteStaticPublicKey = rs
                ss.mixKey(e!!.agree(rs)) // es
            }
            2 -> {
                val rs = ss.decryptAndHash(take(DH_LEN + TAG_LEN)) // s
                remoteStaticPublicKey = rs
                ss.mixKey(e!!.agree(rs)) // se
            }
        }
        val payload = ss.decryptAndHash(message.copyOfRange(off, message.size))
        step++
        return payload
    }

    fun split(): NoiseTransport {
        check(isComplete) { "handshake not complete" }
        val (c1, c2) = ss.split()
        return if (initiator) NoiseTransport(c1, c2) else NoiseTransport(c2, c1)
    }

    companion object {
        fun initiator(
            s: DhKey,
            prologue: ByteArray,
            newEphemeral: () -> X25519KeyPair = { X25519.generate() },
        ) = NoiseHandshake(true, s, prologue, newEphemeral)

        fun responder(
            s: DhKey,
            prologue: ByteArray,
            newEphemeral: () -> X25519KeyPair = { X25519.generate() },
        ) = NoiseHandshake(false, s, prologue, newEphemeral)
    }
}

class NoiseTransport internal constructor(
    private val send: CipherState,
    private val recv: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray = send.encryptWithAd(EMPTY, plaintext)
    fun decrypt(ciphertext: ByteArray): ByteArray = recv.decryptWithAd(EMPTY, ciphertext)
}
