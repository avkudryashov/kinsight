package app.kinsight.core.sync

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MemoryPipeTest {
    @Test
    fun writeAfterPeerCloseThrowsIOException(): Unit = runBlocking {
        val (a, b) = MemoryPipe.pair()
        a.close()
        assertFailsWith<IOException> { b.write(byteArrayOf(1)) }
    }

    @Test
    fun readAfterPeerCloseThrowsIOException(): Unit = runBlocking {
        val (a, b) = MemoryPipe.pair()
        a.close()
        assertFailsWith<IOException> { b.readFully(1) }
    }
}
