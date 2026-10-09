package app.kinsight.core.keystore

import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kinsight.core.crypto.CryptoException
import app.kinsight.core.crypto.Hpke
import app.kinsight.core.crypto.NoiseHandshake
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import javax.crypto.KeyAgreement
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {
    private val backend = KeystoreKeys(signAlias = SIGN, dhAlias = DH)
    private lateinit var keys: DeviceKeys

    @Before
    fun setUp() {
        val created = runCatching { backend.create() }
        created.exceptionOrNull()?.let { Log.w(TAG, "Keystore Curve25519 unavailable", it) }
        assumeTrue("Keystore cannot create Curve25519 keys on this device", created.isSuccess)
        keys = created.getOrThrow()
    }

    @After
    fun tearDown() {
        backend.delete()
        KeystoreKeys.keyStore().deleteEntry(WRAP)
    }

    @Test
    fun keysAreHardwareBacked(): Unit = runBlocking {
        val levels = backend.securityLevels()
        Log.i(TAG, "security levels $levels")
        assertEquals(2, levels.size)
        for (level in levels) {
            assertTrue(
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || level == KeyProperties.SECURITY_LEVEL_STRONGBOX,
                "security level $level",
            )
        }
    }

    @Test
    fun passesSelfTest(): Unit = runBlocking {
        val r = KeySelfTest.run(keys)
        assertTrue(r.passed, r.detail)
    }

    @Test
    fun loadReturnsSameKeys(): Unit = runBlocking {
        val again = KeystoreKeys(signAlias = SIGN, dhAlias = DH).load()
        assertEquals(keys.deviceId, again.deviceId)
        assertContentEquals(keys.dh.publicKey, again.dh.publicKey)
    }

    @Test
    fun createReplacesExistingKeys(): Unit = runBlocking {
        val second = backend.create()
        assertFalse(second.deviceId == keys.deviceId, "second create kept the old signing key")
        assertFalse(second.dh.publicKey.contentEquals(keys.dh.publicKey), "second create kept the old DH key")
        val loaded = backend.load()
        assertEquals(second.deviceId, loaded.deviceId)
        assertContentEquals(second.dh.publicKey, loaded.dh.publicKey)
    }

    @Test
    fun hasKeysTracksSignAndDhAliasesOnly(): Unit = runBlocking {
        assertTrue(backend.hasKeys())
        val ks = KeystoreKeys.keyStore()
        ks.deleteEntry(SIGN)
        assertTrue(backend.hasKeys(), "dh alias alone counts")
        backend.delete()
        assertFalse(backend.hasKeys())
        // The wrap key belongs to the software backend and must not count as Keystore device keys.
        keystoreAesKey(InstrumentationRegistry.getInstrumentation().targetContext, WRAP)
        assertFalse(backend.hasKeys())
        backend.create()
        ks.deleteEntry(DH)
        assertTrue(backend.hasKeys(), "sign alias alone counts")
    }

    @Test
    fun jcaSoftwarePeerAgreesAfterReimport(): Unit = runBlocking {
        val peer = KeyPairGenerator.getInstance("XDH").generateKeyPair()
        val ours = keys.dh.agree(Spki.x25519Raw(peer.public.encoded))
        val theirs = KeyAgreement.getInstance("XDH").run {
            init(peer.private)
            doPhase(Spki.x25519PublicKey(keys.dh.publicKey), true)
            generateSecret()
        }
        assertContentEquals(theirs, ours)
    }

    @Test
    fun agreeRejectsBadLengthAndLowOrder(): Unit = runBlocking {
        assertFailsWith<CryptoException> { keys.dh.agree(ByteArray(31)) }
        assertFailsWith<CryptoException> { keys.dh.agree(ByteArray(33)) }
        assertFailsWith<CryptoException> { keys.dh.agree(ByteArray(32)) }
        assertFailsWith<CryptoException> { keys.dh.agree(ByteArray(32).also { it[0] = 1 }) }
    }

    @Test
    fun operationsOnDeletedKeysThrowCryptoException(): Unit = runBlocking {
        backend.delete()
        assertFailsWith<CryptoException> { keys.sign.sign(byteArrayOf(1, 2, 3)) }
        assertFailsWith<CryptoException> { keys.dh.agree(X25519.generate().publicKey) }
    }

    @Test
    fun noiseHandshakeWithSoftwarePeer(): Unit = runBlocking {
        val peer = X25519.generate()
        val prologue = "kinsight-device-test".encodeToByteArray()
        val i = NoiseHandshake.initiator(keys.dh, prologue)
        val r = NoiseHandshake.responder(peer, prologue)
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        assertContentEquals(keys.dh.publicKey, r.remoteStaticPublicKey)
        assertEquals("hi", r.split().decrypt(i.split().encrypt("hi".encodeToByteArray())).decodeToString())
    }

    @Test
    fun hpkeOpensWithKeystoreKey(): Unit = runBlocking {
        val info = "kinsight-discovery-key".encodeToByteArray()
        val sealed = Hpke.seal(keys.dh.publicKey, info, byteArrayOf(1), "discovery".encodeToByteArray())
        assertEquals("discovery", Hpke.open(keys.dh, info, byteArrayOf(1), sealed).decodeToString())
    }

    @Test
    fun signsVerifiableGroupGenesis(): Unit = runBlocking {
        val g = GroupGenesis.create(keys, "phone")
        assertTrue(g.entry.verifySignature(keys.sign.publicKey))
        assertNotNull(Membership.compute(g.groupId, listOf(g.entry)))
    }

    @Test
    fun loadAfterDeleteFails(): Unit = runBlocking {
        backend.delete()
        assertFailsWith<KeyStorageException> { backend.load() }
    }

    private companion object {
        const val TAG = "KinsightKeystoreTest"
        const val SIGN = "kinsight.test.sign"
        const val DH = "kinsight.test.dh"
        const val WRAP = "kinsight.test.keystore-keys.wrap"
    }
}
