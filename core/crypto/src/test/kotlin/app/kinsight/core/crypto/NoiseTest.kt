package app.kinsight.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoiseTest {
    private val prologue = "kinsight-test".encodeToByteArray()

    private fun handshake(
        i: NoiseHandshake,
        r: NoiseHandshake,
    ): Pair<NoiseTransport, NoiseTransport> {
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        assertTrue(i.isComplete && r.isComplete)
        return i.split() to r.split()
    }

    @Test
    fun handshakeAuthenticatesBothStaticKeys() {
        val si = X25519.generate()
        val sr = X25519.generate()
        val i = NoiseHandshake.initiator(si, prologue)
        val r = NoiseHandshake.responder(sr, prologue)
        val (ti, tr) = handshake(i, r)
        assertContentEquals(sr.publicKey, i.remoteStaticPublicKey)
        assertContentEquals(si.publicKey, r.remoteStaticPublicKey)
        assertContentEquals(i.handshakeHash, r.handshakeHash)
        assertEquals("ping", tr.decrypt(ti.encrypt("ping".encodeToByteArray())).decodeToString())
        assertEquals("pong", ti.decrypt(tr.encrypt("pong".encodeToByteArray())).decodeToString())
    }

    @Test
    fun differentProloguesFail() {
        val i = NoiseHandshake.initiator(X25519.generate(), "a".encodeToByteArray())
        val r = NoiseHandshake.responder(X25519.generate(), "b".encodeToByteArray())
        r.readMessage(i.writeMessage())
        assertFailsWith<CryptoException> { i.readMessage(r.writeMessage()) }
    }

    @Test
    fun tamperedTransportMessageFails() {
        val (ti, tr) = handshake(
            NoiseHandshake.initiator(X25519.generate(), prologue),
            NoiseHandshake.responder(X25519.generate(), prologue),
        )
        val ct = ti.encrypt("x".encodeToByteArray())
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertFailsWith<CryptoException> { tr.decrypt(ct) }
    }

    @Test
    fun replayedTransportMessageFails() {
        val (ti, tr) = handshake(
            NoiseHandshake.initiator(X25519.generate(), prologue),
            NoiseHandshake.responder(X25519.generate(), prologue),
        )
        val ct = ti.encrypt("x".encodeToByteArray())
        tr.decrypt(ct)
        assertFailsWith<CryptoException> { tr.decrypt(ct) }
    }

    @Test
    fun truncatedHandshakeMessageFails() {
        val i = NoiseHandshake.initiator(X25519.generate(), prologue)
        val r = NoiseHandshake.responder(X25519.generate(), prologue)
        r.readMessage(i.writeMessage())
        val m2 = r.writeMessage()
        assertFailsWith<CryptoException> { i.readMessage(m2.copyOf(40)) }
    }

    @Test
    fun cacophonyVectors() {
        val text = javaClass.getResource("/noise-xx-vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray
        for (v in vectors) {
            val o = v.jsonObject
            fun hex(name: String) = o[name]!!.jsonPrimitive.content.hexToBytes()
            val i = NoiseHandshake.initiator(X25519.fromPrivate(hex("init_static")), hex("init_prologue")) {
                X25519.fromPrivate(hex("init_ephemeral"))
            }
            val r = NoiseHandshake.responder(X25519.fromPrivate(hex("resp_static")), hex("resp_prologue")) {
                X25519.fromPrivate(hex("resp_ephemeral"))
            }
            val messages = o["messages"]!!.jsonArray.map { it.jsonObject }
            var ti: NoiseTransport? = null
            var tr: NoiseTransport? = null
            messages.forEachIndexed { idx, m ->
                val payload = m["payload"]!!.jsonPrimitive.content.hexToBytes()
                val expected = m["ciphertext"]!!.jsonPrimitive.content.hexToBytes()
                val fromInitiator = idx % 2 == 0
                if (idx < 3) {
                    val (w, rd) = if (fromInitiator) i to r else r to i
                    val ct = w.writeMessage(payload)
                    assertEquals(expected.toHex(), ct.toHex(), "handshake message $idx")
                    assertContentEquals(payload, rd.readMessage(ct))
                    if (idx == 2) {
                        assertEquals(hex("handshake_hash").toHex(), i.handshakeHash.toHex())
                        ti = i.split()
                        tr = r.split()
                    }
                } else {
                    val (w, rd) = if (fromInitiator) ti!! to tr!! else tr!! to ti!!
                    val ct = w.encrypt(payload)
                    assertEquals(expected.toHex(), ct.toHex(), "transport message $idx")
                    assertContentEquals(payload, rd.decrypt(ct))
                }
            }
        }
    }
}
