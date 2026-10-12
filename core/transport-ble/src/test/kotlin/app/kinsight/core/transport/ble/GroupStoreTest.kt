package app.kinsight.core.transport.ble

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.GroupId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupStoreTest {
    private val dir = Files.createTempDirectory("kinsight-group").toFile()
    private val file = File(dir, GroupStore.FILE_NAME)
    private val config = GroupConfig(GroupId.fromBytes(Primitives.randomBytes(16)), Primitives.randomBytes(32))

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun absentFileMeansNoGroup() {
        assertNull(GroupStore(file).read())
        assertFalse(GroupStore(file).exists())
    }

    @Test
    fun writtenGroupSurvivesANewInstance() {
        GroupStore(file).write(config)
        val again = GroupStore(file).read()!!
        assertEquals(config.groupId, again.groupId)
        assertContentEquals(config.discoveryKey, again.discoveryKey)
        assertTrue(GroupStore(file).exists())
        assertEquals(listOf(GroupStore.FILE_NAME), dir.list()!!.toList()) // no temp file left behind
    }

    @Test
    fun corruptFileIsAnErrorNotAMissingGroup() {
        file.writeBytes(ByteArray(53) { 7 })
        assertFailsWith<GroupStoreException> { GroupStore(file).read() }
        file.writeBytes(ByteArray(10))
        assertFailsWith<GroupStoreException> { GroupStore(file).read() }
    }
}
