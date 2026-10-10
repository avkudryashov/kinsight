package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.GroupId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GroupStoreDeleteTest {
    private val dir = Files.createTempDirectory("kinsight-group-delete").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun deleteForgetsTheGroup() {
        val store = GroupStore(File(dir, GroupStore.FILE_NAME))
        store.write(GroupConfig(GroupId.fromBytes(Primitives.randomBytes(16)), Primitives.randomBytes(32)))
        store.read()
        store.delete()
        assertFalse(store.exists())
        assertNull(store.read())
        store.delete() // idempotent
    }
}
