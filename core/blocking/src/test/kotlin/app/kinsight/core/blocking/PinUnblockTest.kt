package app.kinsight.core.blocking

import app.kinsight.core.data.PinCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Review M4: the PIN override is saved inside the error handling; a failed save keeps the block screen open. */
class PinUnblockTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = mutableListOf<String>()

    private suspend fun unlock(check: PinCheck, saveFails: Boolean = false, signFails: Boolean = false) = PinUnblock.unlock(
        scope,
        check = { check },
        save = { calls += "save"; if (saveFails) throw IOException("disk full") },
        sign = { calls += "sign"; if (signFails) throw IllegalStateException("no group") },
    )

    @Test
    fun aRightPinSavesThenSigns() = runBlocking {
        assertEquals(PinCheck.OK, unlock(PinCheck.OK))
        assertEquals(listOf("save", "sign"), calls)
    }

    @Test
    fun aFailedSaveThrowsAndSignsNothing() = runBlocking {
        val thrown = runCatching { unlock(PinCheck.OK, saveFails = true) }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is PinUnblock.NotSaved)
        assertEquals(listOf("save"), calls)
        assertTrue("the app scope lives on", scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun aFailedSignatureStillOpensTheApp() = runBlocking {
        assertEquals(PinCheck.OK, unlock(PinCheck.OK, signFails = true))
        assertEquals(listOf("save", "sign"), calls)
    }

    @Test
    fun aWrongPinSavesNothing() = runBlocking {
        assertEquals(PinCheck.WRONG, unlock(PinCheck.WRONG))
        assertEquals(emptyList<String>(), calls)
    }
}
