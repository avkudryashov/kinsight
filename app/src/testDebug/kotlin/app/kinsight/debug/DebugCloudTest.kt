package app.kinsight.debug

import app.kinsight.core.data.CloudGraph
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.YandexDisk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DebugCloudTest {
    @AfterTest
    fun cleanUp() {
        CloudGraph.fakeDisk = null
    }

    @Test
    fun theFakeDiskReplacesYandexUntilSwitchedOff() {
        assertEquals("fakeDisk=true", DebugCloud.fakeDisk(true))
        val fake = assertIs<InMemoryDisk>(CloudGraph.disk { "t" })
        DebugCloud.fakeDisk(true)
        assertSame(fake, CloudGraph.disk { "t" }, "switching on twice keeps the same Disk and its files")
        assertEquals("fakeDisk=false", DebugCloud.fakeDisk(false))
        assertIs<YandexDisk>(CloudGraph.disk { "t" })
    }
}
