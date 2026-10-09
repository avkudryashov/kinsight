package app.kinsight.core.keystore

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Signer
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBackend(
    override val kind: KeyBackendKind,
    var createError: Exception? = null,
    var keysFactory: () -> DeviceKeys = { DeviceKeys.generate() },
) : KeyBackend {
    var stored: DeviceKeys? = null
    @Volatile var createCalls = 0
    var deleteCalls = 0

    override fun create(): DeviceKeys {
        createCalls++
        createError?.let { throw it }
        return keysFactory().also { stored = it }
    }

    override fun load(): DeviceKeys = stored ?: throw KeyStorageException("no ${kind.name} keys")

    override fun delete() {
        deleteCalls++
        stored = null
    }
}

private class MemoryChoiceStore(var value: BackendChoice? = null) : BackendChoiceStore {
    override fun read() = value
    override fun write(choice: BackendChoice) {
        value = choice
    }
}

private fun keysWithBrokenSigner(): DeviceKeys {
    val inner = Ed25519.generate()
    val broken = object : Signer {
        override val publicKey = inner.publicKey
        override fun sign(message: ByteArray) = ByteArray(64)
    }
    return DeviceKeys(broken, X25519.generate())
}

private fun keysWithBrokenDh(): DeviceKeys {
    val inner = X25519.generate()
    val broken = object : DhKey {
        override val publicKey = inner.publicKey
        override fun agree(peerPublicKey: ByteArray) = ByteArray(32) { 7 }
    }
    return DeviceKeys(Ed25519.generate(), broken)
}

class KeyBackendSelectorTest {
    private val keystore = FakeBackend(KeyBackendKind.KEYSTORE)
    private val software = FakeBackend(KeyBackendKind.SOFTWARE)
    private val choice = MemoryChoiceStore()
    private val selector = KeyBackendSelector(choice, keystore, software)
    private val dir = Files.createTempDirectory("kinsight-choice").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun firstRunPrefersKeystoreWhenSelfTestPasses() {
        val s = selector.select()
        assertEquals(KeyBackendKind.KEYSTORE, s.kind)
        assertTrue(s.firstRun)
        assertTrue(s.selfTest.passed)
        assertNull(s.fallbackReason)
        assertEquals(BackendChoice(KeyBackendKind.KEYSTORE, null), choice.value)
        assertEquals(0, software.createCalls)
    }

    @Test
    fun firstRunFallsBackWhenKeystoreCannotCreate() {
        keystore.createError = KeyStorageException("no curve25519")
        val s = selector.select()
        assertEquals(KeyBackendKind.SOFTWARE, s.kind)
        assertTrue("no curve25519" in s.fallbackReason!!, s.fallbackReason)
        assertEquals(KeyBackendKind.SOFTWARE, choice.value!!.kind)
        assertEquals(1, keystore.deleteCalls)
    }

    @Test
    fun firstRunFallsBackWhenKeystoreSelfTestFails() {
        keystore.keysFactory = ::keysWithBrokenSigner
        val s = selector.select()
        assertEquals(KeyBackendKind.SOFTWARE, s.kind)
        assertTrue("self-test" in s.fallbackReason!!, s.fallbackReason)
        assertNull(keystore.stored)
    }

    @Test
    fun softwareSelfTestFailureIsFatalAndPersistsNothing() {
        keystore.createError = KeyStorageException("no curve25519")
        software.keysFactory = ::keysWithBrokenDh
        assertFailsWith<KeyStorageException> { selector.select() }
        assertNull(choice.value)
    }

    @Test
    fun laterRunsLoadTheChosenBackend() {
        val first = selector.select()
        val second = selector.select()
        assertFalse(second.firstRun)
        assertEquals(first.keys.deviceId, second.keys.deviceId)
        assertEquals(1, keystore.createCalls)
    }

    @Test
    fun persistedKeystoreChoiceWithMissingKeysFailsLoudly() {
        selector.select()
        keystore.stored = null // keys lost
        assertFailsWith<KeyStorageException> { selector.select() }
        assertEquals(0, software.createCalls)
        assertEquals(1, keystore.createCalls)
        assertEquals(KeyBackendKind.KEYSTORE, choice.value!!.kind)
    }

    @Test
    fun persistedSoftwareChoiceNeverSwitchesBack() {
        choice.value = BackendChoice(KeyBackendKind.SOFTWARE, "earlier failure")
        software.stored = DeviceKeys.generate()
        val s = selector.select()
        assertEquals(KeyBackendKind.SOFTWARE, s.kind)
        assertEquals("earlier failure", s.fallbackReason)
        assertEquals(0, keystore.createCalls)
    }

    @Test
    fun laterSelfTestFailureIsReportedWithoutSwitching() {
        choice.value = BackendChoice(KeyBackendKind.KEYSTORE, null)
        keystore.stored = keysWithBrokenDh()
        val s = selector.select()
        assertEquals(KeyBackendKind.KEYSTORE, s.kind)
        assertFalse(s.selfTest.passed)
        assertEquals(0, software.createCalls)
    }

    @Test
    fun interruptedFirstRunStartsOver() {
        keystore.create() // keys were made, then the process died before the choice was written
        val s = selector.select()
        assertTrue(s.firstRun)
        assertEquals(2, keystore.createCalls)
        assertEquals(keystore.stored!!.sign.publicKey.toList(), s.keys.sign.publicKey.toList())
        assertNotNull(choice.value)
    }

    @Test
    fun concurrentFirstRunsCreateOneIdentity() {
        val shared = MemoryChoiceStore()
        val ks = FakeBackend(KeyBackendKind.KEYSTORE, keysFactory = { Thread.sleep(200); DeviceKeys.generate() })
        val sw = FakeBackend(KeyBackendKind.SOFTWARE)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = List(2) {
            pool.submit<KeySelection> {
                start.await()
                KeyBackendSelector(shared, ks, sw).select()
            }
        }
        start.countDown()
        val (a, b) = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, ks.createCalls)
        assertEquals(a.keys.deviceId, b.keys.deviceId)
        assertEquals(1, listOf(a, b).count { it.firstRun })
    }

    @Test
    fun choiceFileRoundTripsAndFlattensReason() {
        val store = FileChoiceStore(File(dir, "choice"))
        assertNull(store.read())
        store.write(BackendChoice(KeyBackendKind.SOFTWARE, "line one\nline two"))
        assertEquals(BackendChoice(KeyBackendKind.SOFTWARE, "line one line two"), store.read())
        store.write(BackendChoice(KeyBackendKind.KEYSTORE, null))
        assertEquals(BackendChoice(KeyBackendKind.KEYSTORE, null), FileChoiceStore(File(dir, "choice")).read())
    }

    @Test
    fun corruptChoiceFailsLoudly() {
        val file = File(dir, "choice")
        val store = FileChoiceStore(file)
        for (garbage in listOf("", "v1\n", "v2\nKEYSTORE\n", "v1\nTPM\n", "\u0000\u0001")) {
            file.writeText(garbage)
            assertFailsWith<KeyStorageException>("content: $garbage") { store.read() }
        }
        val selector = KeyBackendSelector(store, keystore, software)
        assertFailsWith<KeyStorageException> { selector.select() }
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
    }
}
