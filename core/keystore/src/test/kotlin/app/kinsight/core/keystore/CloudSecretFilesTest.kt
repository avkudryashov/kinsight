package app.kinsight.core.keystore

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSecretFilesTest {
    private val dir = Files.createTempDirectory("kinsight-cloud").toFile()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val wrap = AesGcmWrap { aes }
    private val vaultFile = File(dir, CloudTokenVault.FILE_NAME)
    private val vault = CloudTokenVault(vaultFile, wrap)
    private val keyFile = File(dir, GroupKeyFile.FILE_NAME)
    private val groupKeys = GroupKeyFile(keyFile, wrap)
    private val group = "dd".repeat(16)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun owner(access: String = "ACCESS-TOKEN-1", refresh: String? = "REFRESH-TOKEN-1") =
        CloudTokens(group, owner = true, storageGeneration = 1, storageId = ByteArray(16) { 7 }, accessToken = access, refreshToken = refresh, expiresAtMs = 99)

    @Test
    fun tokensSurviveARestartSealed() {
        assertNull(vault.read())
        vault.write(owner())
        val back = CloudTokenVault(vaultFile, wrap).read()!!
        assertEquals("ACCESS-TOKEN-1", back.accessToken)
        assertEquals("REFRESH-TOKEN-1", back.refreshToken)
        assertTrue(back.owner)
        assertEquals(1L, back.storageGeneration)
        assertContentEquals(ByteArray(16) { 7 }, back.storageId)
        val raw = vaultFile.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse("ACCESS-TOKEN" in raw || "REFRESH-TOKEN" in raw, "tokens must not be on disk in clear")
    }

    @Test
    fun tokensNeverShowInText() {
        val text = owner().toString()
        assertFalse("ACCESS" in text || "REFRESH" in text, text)
    }

    @Test
    fun onlyTheOwnerKeepsARefreshToken() {
        assertFailsWith<IllegalArgumentException> {
            CloudTokens(group, owner = false, storageGeneration = 1, storageId = ByteArray(16), accessToken = "a", refreshToken = "r", expiresAtMs = 1)
        }
    }

    @Test
    fun aTamperedVaultIsAKeyStorageError() {
        vault.write(owner())
        val bytes = vaultFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        vaultFile.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { vault.read() }
    }

    @Test
    fun deleteLeavesNothing() {
        vault.write(owner())
        groupKeys.write(GroupKeyFile.Contents(group, mapOf(0 to ByteArray(32) { 1 })))
        vault.delete()
        groupKeys.delete()
        assertNull(vault.read())
        assertNull(groupKeys.read())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun groupKeysSurviveARestartSealed() {
        val keys = mapOf(0 to ByteArray(32) { 1 }, 3 to ByteArray(32) { 3 })
        groupKeys.write(GroupKeyFile.Contents(group, keys))
        val back = GroupKeyFile(keyFile, wrap).read()!!
        assertEquals(group, back.groupHex)
        assertEquals(setOf(0, 3), back.keys.keys)
        assertContentEquals(keys.getValue(3), back.keys.getValue(3))
        assertEquals("GroupKeyFile.Contents(group=dddddddd, epochs=[0, 3])", back.toString()) // epochs only, never key bytes
    }

    @Test
    fun aGroupKeyOfTheWrongSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { GroupKeyFile.Contents(group, mapOf(0 to ByteArray(31))) }
    }

    @Test
    fun aTamperedGroupKeyFileIsAKeyStorageError() {
        groupKeys.write(GroupKeyFile.Contents(group, mapOf(0 to ByteArray(32) { 1 })))
        val bytes = keyFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        keyFile.writeBytes(bytes)
        assertFailsWith<KeyStorageException> { groupKeys.read() }
    }

    @Test
    fun aBlobOfOneFileIsRefusedAsTheOther() {
        vault.write(owner())
        groupKeys.write(GroupKeyFile.Contents(group, mapOf(0 to ByteArray(32) { 1 })))
        val vaultBytes = vaultFile.readBytes()
        val keyBytes = keyFile.readBytes()
        // swap the bodies behind the magic: the header is then right for the reader but the AAD binding must refuse it
        vaultFile.writeBytes("KCT1".encodeToByteArray() + keyBytes.copyOfRange(4, keyBytes.size))
        keyFile.writeBytes("KGK1".encodeToByteArray() + vaultBytes.copyOfRange(4, vaultBytes.size))
        assertFailsWith<KeyStorageException> { vault.read() }
        assertFailsWith<KeyStorageException> { groupKeys.read() }
        keyFile.writeBytes(vaultBytes)
        assertFailsWith<KeyStorageException> { groupKeys.read() }
    }

    @Test
    fun deleteAlsoRemovesAStaleTempFile() {
        vault.write(owner())
        val stale = File(dir, CloudTokenVault.FILE_NAME + "123.tmp").apply { writeBytes(byteArrayOf(1)) }
        vault.delete()
        assertFalse(stale.exists())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anOversizedTokenIsAKeyStorageError() {
        assertFailsWith<KeyStorageException> { vault.write(owner(access = "x".repeat(70_000))) }
    }

    @Test
    fun heldValuesAreCopies() {
        val id = ByteArray(16) { 7 }
        val t = CloudTokens(group, true, 1, id, "a", "r", 1)
        id[0] = 0
        assertEquals(7, t.storageId[0].toInt())
        val key = ByteArray(32) { 1 }
        val c = GroupKeyFile.Contents(group, mapOf(0 to key))
        key[0] = 0
        assertEquals(1, c.keys.getValue(0)[0].toInt())
    }

    @Test
    fun cloudFilesHandOutOneInstancePerFile() {
        val a = CloudFiles.vault(vaultFile) { wrap }
        assertTrue(a === CloudFiles.vault(File(dir, "./" + CloudTokenVault.FILE_NAME)) { wrap })
        assertTrue(CloudFiles.groupKeys(keyFile) { wrap } === CloudFiles.groupKeys(keyFile) { wrap })
    }
}
