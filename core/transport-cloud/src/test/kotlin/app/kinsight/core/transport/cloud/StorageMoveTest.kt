package app.kinsight.core.transport.cloud

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageMoveTest {
    private val old = InMemoryDisk()
    private val new = InMemoryDisk()

    private fun packs(d: InMemoryDisk, root: String) =
        d.files(root).filterKeys { !it.endsWith("/${CloudPaths.STORAGE_ID}") }.mapValues { it.value.toList() }

    @Test
    fun theCopyTakesEveryDeviceFolderButNotTheStorageId() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = mom.store.groupId
        kid.publish(kid.author.summary("2026-10-13", 60_000))
        kid.sync(old).run()
        mom.sync(old).run()
        StorageMove.writeStorageId(old, g, ByteArray(16) { 1 })
        assertTrue(StorageMove.copy(old, new, g) >= 2, "Mom's and the kid's packs")
        assertEquals(packs(old, CloudPaths.root(g)), packs(new, CloudPaths.root(g)))
        assertNull(StorageMove.storageIdOf(new, g), "the new storage gets its own id")
        assertEquals(0, StorageMove.copy(old, new, g), "a repeated copy finds every file there")
    }

    @Test
    fun aFolderThatIsNotADevicesStaysBehind() = runTest {
        val (mom) = family()
        val g = mom.store.groupId
        for (d in listOf(CloudPaths.APP_ROOT, CloudPaths.root(g), "${CloudPaths.root(g)}/notes")) old.mkdir(d)
        old.upload("${CloudPaths.root(g)}/notes/a.txt", byteArrayOf(1))
        assertEquals(0, StorageMove.copy(old, new, g))
        assertTrue(new.files("${CloudPaths.root(g)}/notes").isEmpty())
    }

    @Test
    fun theStorageIdIsReadBackAndAnythingElseIsNone() = runTest {
        val (mom) = family()
        val g = mom.store.groupId
        assertNull(StorageMove.storageIdOf(old, g))
        StorageMove.writeStorageId(old, g, ByteArray(16) { 4 })
        assertContentEquals(ByteArray(16) { 4 }, StorageMove.storageIdOf(old, g))
        old.upload(CloudPaths.storageIdFile(g), byteArrayOf(1, 2, 3))
        assertNull(StorageMove.storageIdOf(old, g))
    }

    @Test
    fun aRevokedSharedTokenStopsTheCopy() = runTest {
        val (mom) = family()
        val revoked = object : DiskApi {
            override suspend fun list(path: String): List<DiskItem> = throw DiskException.Unauthorized()
            override suspend fun mkdir(path: String) = throw DiskException.Unauthorized()
            override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.Unauthorized()
            override suspend fun download(path: String): ByteArray = throw DiskException.Unauthorized()
            override suspend fun delete(path: String) = throw DiskException.Unauthorized()
        }
        assertFailsWith<DiskException.Unauthorized> { StorageMove.copy(revoked, new, mom.store.groupId) }
    }
}
