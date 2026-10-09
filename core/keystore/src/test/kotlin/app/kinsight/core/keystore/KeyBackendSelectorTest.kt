package app.kinsight.core.keystore

import app.kinsight.core.crypto.DhKey
import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.Signer
import app.kinsight.core.crypto.X25519
import app.kinsight.core.model.DeviceKeys
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeBackend(
    override val kind: KeyBackendKind,
    var createError: Throwable? = null,
    var keysFactory: () -> DeviceKeys = { DeviceKeys.generate() },
) : KeyBackend {
    var stored: DeviceKeys? = null
    var loadError: Exception? = null
    var hasKeysError: Exception? = null
    /** Overrides presence; null = present exactly when [stored] is set. */
    var present: Boolean? = null
    var loadCalls = 0
    @Volatile var createCalls = 0
    var deleteCalls = 0

    override fun create(): DeviceKeys {
        createCalls++
        createError?.let { throw it }
        return keysFactory().also { stored = it }
    }

    override fun load(): DeviceKeys {
        loadCalls++
        return loadError?.let { throw it } ?: stored ?: throw KeyStorageException("no ${kind.name} keys")
    }

    override fun hasKeys(): Boolean {
        hasKeysError?.let { throw it }
        return present ?: (stored != null)
    }

    override fun delete() {
        deleteCalls++
        stored = null
    }
}

private class MemoryChoiceStore(var value: BackendChoice? = null) : BackendChoiceStore {
    var pending = false

    override fun read(): ChoiceState = value?.let { ChoiceState.Chosen(it) }
        ?: if (pending) ChoiceState.Pending else ChoiceState.Absent

    override fun writePending() {
        value = null
        pending = true
    }

    override fun write(choice: BackendChoice) {
        value = choice
        pending = false
    }

    override fun <T> exclusive(block: () -> T): T = block()
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
        assertTrue(choice.pending)
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
        // The previous first run marked the choice pending, made keys, then died before the choice was written.
        choice.writePending()
        keystore.create()
        val s = selector.select()
        assertTrue(s.firstRun)
        assertEquals(2, keystore.createCalls)
        assertEquals(keystore.stored!!.sign.publicKey.toList(), s.keys.sign.publicKey.toList())
        assertEquals(BackendChoice(KeyBackendKind.KEYSTORE, null), choice.value)
        assertFalse(choice.pending)
    }

    @Test
    fun firstRunMarksPendingBeforeCreatingKeys() {
        var stateAtCreate: ChoiceState? = null
        keystore.keysFactory = { stateAtCreate = choice.read(); DeviceKeys.generate() }
        selector.select()
        assertEquals(ChoiceState.Pending, stateAtCreate)
    }

    @Test
    fun lostChoiceWithExistingKeystoreKeysFailsLoudly() {
        keystore.stored = DeviceKeys.generate()
        val e = assertFailsWith<KeyStorageException> { selector.select() }
        assertTrue("without a backend choice" in e.message!!, e.message)
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
        assertEquals(0, keystore.deleteCalls)
        assertEquals(ChoiceState.Absent, choice.read())
    }

    @Test
    fun lostChoiceWithExistingSoftwareKeysFailsLoudly() {
        software.stored = DeviceKeys.generate()
        assertFailsWith<KeyStorageException> { selector.select() }
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
        assertEquals(0, software.deleteCalls)
        assertEquals(ChoiceState.Absent, choice.read())
    }

    @Test
    fun lostChoiceWithPresentButUnloadableKeystoreKeysFailsLoudly() {
        keystore.present = true
        keystore.loadError = IllegalStateException("TEE busy")
        val e = assertFailsWith<KeyStorageException> { selector.select() }
        assertTrue("without a backend choice" in e.message!!, e.message)
        assertEquals(0, keystore.loadCalls)
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
        assertEquals(ChoiceState.Absent, choice.read())
    }

    @Test
    fun lostChoiceWithUnanswerablePresenceFailsLoudly() {
        for (backend in listOf(keystore, software)) {
            backend.hasKeysError = IllegalStateException("keystore daemon down")
            val e = assertFailsWith<KeyStorageException> { selector.select() }
            assertTrue(e.cause is IllegalStateException, e.toString())
            backend.hasKeysError = null
        }
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
        assertEquals(ChoiceState.Absent, choice.read())
    }

    @Test
    fun absentChoiceWithNoKeysIsAFirstRun() {
        val s = selector.select()
        assertTrue(s.firstRun)
        assertEquals(0, keystore.loadCalls)
        assertEquals(0, software.loadCalls)
        assertEquals(1, keystore.createCalls)
    }

    @Test
    fun persistedChoiceWithUnexpectedLoadFailureIsKeyStorageException() {
        choice.value = BackendChoice(KeyBackendKind.KEYSTORE, null)
        keystore.loadError = IllegalStateException("TEE gone")
        val e = assertFailsWith<KeyStorageException> { selector.select() }
        assertTrue(e.cause is IllegalStateException)
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
    }

    @Test
    fun firstRunDoesNotFallBackOnErrorOrCancellation() {
        for (t in listOf<Throwable>(OutOfMemoryError("test"), CancellationException("cancelled"))) {
            keystore.createError = t
            val thrown = assertFails { selector.select() }
            assertSame(t, thrown)
            assertEquals(0, software.createCalls)
            assertNull(choice.value)
        }
    }

    @Test
    fun keystoreFirstRunDeletesStaleSoftwareKeys() {
        choice.writePending()
        software.stored = DeviceKeys.generate()
        val s = selector.select()
        assertEquals(KeyBackendKind.KEYSTORE, s.kind)
        assertNull(software.stored)
        assertEquals(1, software.deleteCalls)
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
        assertEquals(ChoiceState.Absent, store.read())
        store.write(BackendChoice(KeyBackendKind.SOFTWARE, "line one\nline two"))
        assertEquals(ChoiceState.Chosen(BackendChoice(KeyBackendKind.SOFTWARE, "line one line two")), store.read())
        store.write(BackendChoice(KeyBackendKind.KEYSTORE, null))
        assertEquals(ChoiceState.Chosen(BackendChoice(KeyBackendKind.KEYSTORE, null)), FileChoiceStore(File(dir, "choice")).read())
    }

    @Test
    fun choiceFileRoundTripsPending() {
        val store = FileChoiceStore(File(dir, "choice"))
        store.writePending()
        assertEquals(ChoiceState.Pending, FileChoiceStore(File(dir, "choice")).read())
        store.write(BackendChoice(KeyBackendKind.SOFTWARE, "why"))
        assertEquals(ChoiceState.Chosen(BackendChoice(KeyBackendKind.SOFTWARE, "why")), store.read())
    }

    @Test
    fun choiceFileNamesShorterThanThreeCharsAreRejected() {
        assertFailsWith<IllegalArgumentException> { FileChoiceStore(File(dir, "ch")) }
    }

    @Test
    fun exclusiveHoldsAnOsLockOnASiblingFile() {
        val store = FileChoiceStore(File(dir, "choice"))
        val lockFile = File(dir, "choice.lock")
        store.exclusive {
            assertTrue(lockFile.exists())
            FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { ch ->
                assertFailsWith<OverlappingFileLockException> { ch.tryLock() }
            }
        }
        FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { ch -> ch.tryLock()!!.release() }
    }

    @Test
    fun corruptChoiceFailsLoudly() {
        val file = File(dir, "choice")
        val store = FileChoiceStore(file)
        val corrupt = listOf(
            "", "v1\n", "v2\nKEYSTORE\n", "v1\nTPM\n", "\u0000\u0001",
            "v1\nKEYSTORE\nsome reason", "v1\nPENDING\nsome reason",
        )
        for (garbage in corrupt) {
            file.writeText(garbage)
            assertFailsWith<KeyStorageException>("content: $garbage") { store.read() }
            assertFailsWith<KeyStorageException>("content: $garbage") { KeyBackendSelector(store, keystore, software).select() }
        }
        val selector = KeyBackendSelector(store, keystore, software)
        assertFailsWith<KeyStorageException> { selector.select() }
        assertEquals(0, keystore.createCalls)
        assertEquals(0, software.createCalls)
    }
}
