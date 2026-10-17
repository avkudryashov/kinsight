package app.kinsight.debug

import app.kinsight.core.maps.BlockStore
import app.kinsight.core.maps.Cell
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MAP_INSTALL_LOCAL and MAP_STATUS: a locally built block through the store's own install path, and what is there. */
class DebugMapsTest {
    private val now = 1_760_000_000_000L
    private val root = createTempDirectory("debugmaps").toFile()
    private val store = BlockStore(File(root, "maps")) { 5_000_000L }
    private val valencia = Cell(510, 389)
    private val bytes = "a local block ".repeat(2_000).toByteArray()

    private fun pushed(b: ByteArray = bytes) = File(root, "maps-in/z10-510-389.pmtiles").apply {
        parentFile.mkdirs()
        writeBytes(b)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun aLocalFileBecomesTheCellsBlockAndCountsAsUsedNow() {
        assertEquals("installed 510 389 ${bytes.size}", DebugMaps.installLocal(store, 510, 389, pushed().path, now))
        assertContentEquals(bytes, store.file(valencia).readBytes())
        assertEquals(sha(bytes), store.ledger().installed[valencia])
        assertEquals(now, store.ledger().usage[valencia])
        assertTrue(store.dir.listFiles().orEmpty().none { it.name.endsWith(BlockStore.PART) }, "the partial is gone")
        assertTrue(File(root, "maps-in/z10-510-389.pmtiles").isFile, "the pushed file is left where it was")
    }

    @Test
    fun aNewerMarkIsKept() {
        store.updateLedger { it.copy(usage = mapOf(valencia to now + 5)) }
        DebugMaps.installLocal(store, 510, 389, pushed().path, now)
        assertEquals(now + 5, store.ledger().usage[valencia])
    }

    @Test
    fun badExtrasAreRefusedAndInstallNothing() {
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, null, now) }
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, File(root, "missing").path, now) }
        assertFailsWith<IllegalArgumentException> { DebugMaps.installLocal(store, 1024, 389, pushed().path, now) }
        assertFailsWith<IllegalArgumentException> { DebugMaps.installLocal(store, 510, -1, pushed().path, now) }
        assertFailsWith<IllegalStateException> { DebugMaps.installLocal(store, 510, 389, pushed(ByteArray(0)).path, now) }
        assertFalse(store.file(valencia).exists())
    }

    @Test
    fun theStatusListsCellsMarksCatalogAndFreeBytes() {
        assertEquals("present=none used=none catalog=none free=5000000", DebugMaps.status(store))
        DebugMaps.installLocal(store, 510, 389, pushed().path, now)
        store.updateLedger { it.copy(usage = it.usage + (Cell(511, 389) to now - 7)) }
        store.saveCatalog("""{"version":"2026-10","blocks":[]}""")
        assertEquals("present=510:389 used=510:389@$now,511:389@${now - 7} catalog=2026-10 free=5000000", DebugMaps.status(store))
    }
}
