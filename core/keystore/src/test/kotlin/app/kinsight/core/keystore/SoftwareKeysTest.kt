package app.kinsight.core.keystore

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.crypto.toHex
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SoftwareKeysTest {
    private val dir = Files.createTempDirectory("kinsight-keys").toFile()
    private val file = File(dir, "keys.bin")
    private val aesKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keys = SoftwareKeys(file, AesGcmWrap { aesKey })

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun createThenLoadGivesSameIdentity() {
        val created = keys.create()
        val loaded = SoftwareKeys(file, AesGcmWrap { aesKey }).load()
        assertEquals(created.deviceId, loaded.deviceId)
        assertContentEquals(created.dh.publicKey, loaded.dh.publicKey)
        val msg = "m".encodeToByteArray()
        assertTrue(Ed25519.verify(created.sign.publicKey, msg, loaded.sign.sign(msg)))
        assertEquals(KeyBackendKind.SOFTWARE, keys.kind)
    }

    @Test
    fun fileHoldsNoPlaintextKeyMaterial() {
        keys.create()
        val blob = file.readBytes().toHex()
        val loaded = keys.load()
        // The raw public keys are derivable from the secrets; neither may appear in the file.
        assertFalse(loaded.sign.publicKey.toHex() in blob)
        assertFalse(loaded.dh.publicKey.toHex() in blob)
        assertEquals("KSK1", file.readBytes().copyOf(4).decodeToString())
    }

    @Test
    fun hasKeysReflectsFilePresenceWithoutUnsealing() {
        assertFalse(keys.hasKeys())
        keys.create()
        assertTrue(keys.hasKeys())
        file.writeBytes(byteArrayOf(1, 2, 3)) // unreadable keys are still present keys
        assertTrue(keys.hasKeys())
        keys.delete()
        assertFalse(keys.hasKeys())
    }

    @Test
    fun loadWithoutFileFails() {
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun tamperedFileFailsLoudly() {
        keys.create()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun truncatedFileFailsLoudly() {
        keys.create()
        file.writeBytes(file.readBytes().copyOf(10))
        assertFailsWith<KeyStorageException> { keys.load() }
        file.writeBytes(ByteArray(0))
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun otherWrapKeyCannotOpen() {
        keys.create()
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertFailsWith<KeyStorageException> { SoftwareKeys(file, AesGcmWrap { otherKey }).load() }
    }

    @Test
    fun createReplacesAndDeleteRemoves() {
        val first = keys.create()
        val second = keys.create()
        assertNotEquals(first.deviceId, second.deviceId)
        assertEquals(second.deviceId, keys.load().deviceId)
        keys.delete()
        assertFalse(file.exists())
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun noTempFileIsLeftBehind() {
        keys.create()
        assertEquals(listOf("keys.bin"), dir.list()!!.toList())
    }

    @Test
    fun wrapRejectsDifferentAad() {
        val wrap = AesGcmWrap { aesKey }
        val sealed = wrap.seal(ByteArray(8) { 1 }, "a".encodeToByteArray())
        assertFails { wrap.open(sealed, "b".encodeToByteArray()) }
    }

    @Test
    fun twoSealsUseDifferentIvs() {
        val wrap = AesGcmWrap { aesKey }
        val aad = "a".encodeToByteArray()
        val one = wrap.seal(ByteArray(8), aad)
        val two = wrap.seal(ByteArray(8), aad)
        assertNotEquals(one.copyOf(12).toHex(), two.copyOf(12).toHex())
    }

    @Test
    fun flippedMagicByteFailsLoudly() {
        keys.create()
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun flippedIvByteFailsLoudly() {
        keys.create()
        val bytes = file.readBytes()
        bytes[4] = (bytes[4].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { keys.load() }
    }

    @Test
    fun writeUnderRegularFileSurfacesAsStorageException() {
        val blocker = File(dir, "blocker").apply { writeText("x") }
        assertFailsWith<KeyStorageException> { SoftwareKeys(File(blocker, "keys.bin"), AesGcmWrap { aesKey }).create() }
    }

    @Test
    fun writeIntoReadOnlyDirectorySurfacesAsStorageException() {
        val ro = File(dir, "ro").apply { mkdirs() }
        ro.setWritable(false)
        try {
            assertFailsWith<KeyStorageException> { SoftwareKeys(File(ro, "keys.bin"), AesGcmWrap { aesKey }).create() }
        } finally {
            ro.setWritable(true)
        }
    }

    @Test
    fun targetWithoutParentDirectoryIsStorageException() {
        assertFailsWith<KeyStorageException> { writeAtomically(File("/"), ByteArray(1)) }
    }

    @Test
    fun deleteFailureIsReported() {
        file.mkdirs()
        File(file, "child").writeText("x")
        assertFailsWith<KeyStorageException> { keys.delete() }
    }

    @Test
    fun deleteAlsoRemovesStaleTempFiles() {
        keys.create()
        val stale = File(dir, "keys.bin12345.tmp").apply { writeText("half") }
        keys.delete()
        assertFalse(stale.exists())
        assertEquals(emptyList(), dir.list()!!.toList())
    }
}
