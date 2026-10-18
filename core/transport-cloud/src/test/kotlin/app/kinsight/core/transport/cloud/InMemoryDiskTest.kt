package app.kinsight.core.transport.cloud

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.util.Locale

class InMemoryDiskTest {
    private val disk = InMemoryDisk()

    @Test
    fun uploadNeedsTheParentFolder() = runTest {
        assertFailsWith<DiskException.Conflict> { disk.upload("app:/Kinsight/g/x.kb", byteArrayOf(1)) }
        disk.mkdir("app:/Kinsight")
        disk.mkdir("app:/Kinsight")
        disk.mkdir("app:/Kinsight/g")
        disk.upload("app:/Kinsight/g/x.kb", byteArrayOf(1, 2))
        val item = disk.list("app:/Kinsight/g").single()
        assertEquals("x.kb", item.name)
        assertEquals(2L, item.size)
        assertEquals(64, item.sha256!!.length)
        assertEquals(listOf("g"), disk.list("app:/Kinsight").map { it.name })
    }

    @Test
    fun missingPathsBehaveLikeTheDisk() = runTest {
        assertFailsWith<DiskException.NotFound> { disk.list("app:/Kinsight") }
        assertFailsWith<DiskException.NotFound> { disk.download("app:/Kinsight/x") }
        assertFailsWith<DiskException.Conflict> { disk.mkdir("app:/Kinsight/a/b") }
        disk.delete("app:/Kinsight/nothing")
    }

    @Test
    fun deleteTreeRemovesEverythingBelow() = runTest {
        disk.mkdir("app:/Kinsight")
        disk.mkdir("app:/Kinsight/g")
        disk.upload("app:/Kinsight/g/x.kb", byteArrayOf(1))
        disk.deleteTree("app:/Kinsight/g")
        assertEquals(emptyList(), disk.list("app:/Kinsight"))
    }

    @Test
    fun packNameIgnoresTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))
            assertEquals("00000000000000000001-00000000000000000042.kb", CloudPaths.packName(1, 42))
            assertEquals(1L..42L, CloudPaths.parsePack(CloudPaths.packName(1, 42)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun pathsRoundTrip() {
        assertEquals("00000000000000000001-00000000000000000042.kb", CloudPaths.packName(1, 42))
        assertEquals(1L..42L, CloudPaths.parsePack(CloudPaths.packName(1, 42)))
        assertNull(CloudPaths.parsePack("storage.id"))
        assertNull(CloudPaths.parsePack("00000000000000000005-00000000000000000004.kb"))
        assertNull(CloudPaths.parseDevice("ABC"))
        assertEquals("ab".repeat(16), CloudPaths.parseDevice("ab".repeat(16))!!.hex)
    }
}
