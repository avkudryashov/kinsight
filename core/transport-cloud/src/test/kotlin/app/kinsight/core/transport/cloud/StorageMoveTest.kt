package app.kinsight.core.transport.cloud

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.summary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        val count = old.files(CloudPaths.root(g)).keys.count { !it.endsWith("/${CloudPaths.STORAGE_ID}") }
        assertEquals(2, count, "Mom's and the kid's packs")
        assertEquals(count, StorageMove.copy(old, new, g))
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
    fun aCopyCutShortResumesWithTheMissingFilesOnly() = runTest {
        val (mom, kid) = family(DeviceRole.CHILD)
        val g = mom.store.groupId
        kid.publish(kid.author.summary("2026-10-13", 60_000))
        kid.sync(old).run()
        mom.sync(old).run()
        new.failUploadsAfter = 1
        assertFailsWith<StorageMoveException> { StorageMove.copy(old, new, g) }
        new.failUploadsAfter = null
        assertEquals(1, packs(new, CloudPaths.root(g)).size, "one pack made it before the cut")
        assertEquals(1, StorageMove.copy(old, new, g), "only the missing pack is uploaded")
        assertEquals(packs(old, CloudPaths.root(g)), packs(new, CloudPaths.root(g)))
    }

    @Test
    fun aTruncatedFileOnTheNewDiskIsWrittenAgain() = runTest {
        val (mom) = family()
        val g = mom.store.groupId
        mom.sync(old).run()
        StorageMove.copy(old, new, g)
        val path = old.files(CloudPaths.root(g)).keys.single()
        new.upload(path, byteArrayOf(1))
        assertEquals(1, StorageMove.copy(old, new, g))
        assertEquals(packs(old, CloudPaths.root(g)), packs(new, CloudPaths.root(g)))
    }

    @Test
    fun onlyPacksLeaveADeviceFolder() = runTest {
        val (mom) = family()
        val g = mom.store.groupId
        mom.sync(old).run()
        val dir = old.files(CloudPaths.root(g)).keys.single().substringBeforeLast('/')
        old.upload("$dir/stray.txt", byteArrayOf(9))
        assertEquals(1, StorageMove.copy(old, new, g))
        assertTrue(new.files(CloudPaths.root(g)).keys.none { it.endsWith("stray.txt") })
    }

    private fun unauthorized() = object : DiskApi {
        override suspend fun list(path: String): List<DiskItem> = throw DiskException.Unauthorized()
        override suspend fun mkdir(path: String) = throw DiskException.Unauthorized()
        override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.Unauthorized()
        override suspend fun download(path: String): ByteArray = throw DiskException.Unauthorized()
        override suspend fun delete(path: String) = throw DiskException.Unauthorized()
    }

    @Test
    fun aRevokedSourceTokenIsReportedAsTheSource() = runTest {
        val (mom) = family()
        val e = assertFailsWith<StorageMoveException> { StorageMove.copy(unauthorized(), new, mom.store.groupId) }
        assertEquals(StorageMove.Side.SOURCE, e.side)
        assertIs<DiskException.Unauthorized>(e.cause)
    }

    @Test
    fun aBadDestinationTokenIsReportedAsTheDestination() = runTest {
        val (mom) = family()
        mom.sync(old).run()
        val e = assertFailsWith<StorageMoveException> { StorageMove.copy(old, unauthorized(), mom.store.groupId) }
        assertEquals(StorageMove.Side.DESTINATION, e.side)
        assertIs<DiskException.Unauthorized>(e.cause)
    }
}
