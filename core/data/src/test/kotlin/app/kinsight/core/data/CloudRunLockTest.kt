package app.kinsight.core.data

import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.InMemoryDisk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Final review Important 1: turning the cloud on (or restoring it) and a cloud run or a wipe never overlap. The restore
 * waits for a run in flight, so the run queued after it (CloudWork.TOKEN_POLICY) is the one that sees the new token; a
 * wipe holding the lock keeps an enable out, so no old group's tokens stay behind. Minor 1: a pasted code finishes on the
 * app scope.
 */
class CloudRunLockTest {
    private val mom = TestDevice()
    private val disk = InMemoryDisk()
    private val http = FakeOAuthHttp()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        appScope.cancel()
        mom.close()
    }

    /** Holds a listing on [gate] once set (before the Disk checks the token); [entered]: a run is inside the Disk. */
    private class Gate {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        fun around(inner: DiskApi): DiskApi = object : DiskApi by inner {
            override suspend fun list(path: String): List<DiskItem> {
                gate?.let {
                    entered.complete(Unit)
                    it.await()
                }
                return inner.list(path)
            }
        }
    }

    private suspend fun CloudSignIn.waitUntilWorking() = withTimeout(20_000) {
        while (state.value != SignInState.Working) delay(10)
    }

    private suspend fun enabledWithAT1(onEnabled: () -> Unit = {}): CloudSignIn {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk }, onEnabled)
        s.start("Pixel")
        assertEquals(CloudEnableResult.ENABLED, s.finish("1234567"))
        return s
    }

    @Test
    fun aRestoreWaitsForTheRunInFlightAndTheNextRunIsClean() = runBlocking<Unit> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val s = enabledWithAT1 { events += "enabled" }
        events.clear()
        var accepted = setOf("AT1")
        val gated = Gate()
        val runner = mom.runner { t -> gated.around(TokenDisk(disk, t) { accepted }) }
        // The token dies; the app is opened (a run starts with the dead token) and the owner signs in again.
        accepted = setOf("AT2")
        http.accessToken = "AT2"
        val gate = CompletableDeferred<Unit>().also { gated.gate = it }
        val run = async(Dispatchers.Default) { runner.run(CloudTrigger.NOW).also { events += "run:$it" } }
        withTimeout(20_000) { gated.entered.await() }
        s.start("Pixel")
        val restore = async { s.finish("7654321") }
        s.waitUntilWorking()
        delay(300)
        assertFalse(restore.isCompleted, "the restore waits for the run in flight")
        assertEquals("AT1", mom.cloudSecrets.tokens()!!.accessToken)
        gate.complete(Unit)
        assertEquals(CloudEnableResult.RESTORED, withTimeout(20_000) { restore.await() })
        assertEquals(CloudRun.UNAUTHORIZED, run.await())
        assertEquals(listOf("run:UNAUTHORIZED", "enabled"), events.toList(), "the run after the token comes after the dead one")
        // The run CloudWork queues after the token (APPEND_OR_REPLACE) clears the §11 error.
        gated.gate = null
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW))
        assertNull(mom.cloudState.current().problem)
    }

    @Test
    fun aWipeHoldingTheRunLockKeepsTheRestoreOut() = runBlocking<Unit> {
        val s = enabledWithAT1()
        http.accessToken = "AT2"
        s.start("Pixel")
        mom.cloudSecrets.runLock.lock() // a wipe in progress: LocalReset holds the lock across it
        val restore = async { s.finish("7654321") }
        s.waitUntilWorking()
        delay(300)
        assertFalse(restore.isCompleted, "the restore waits for the wipe")
        assertEquals("AT1", mom.cloudSecrets.tokens()!!.accessToken)
        mom.groups.delete()
        mom.cloudSecrets.forget()
        mom.cloudSecrets.runLock.unlock()
        assertEquals(CloudEnableResult.NOT_ADMIN, withTimeout(20_000) { restore.await() })
        assertNull(mom.cloudSecrets.tokens(), "no token of the old group comes back")
    }

    @Test
    fun aPastedCodeFinishesAfterTheScreenIsGone() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        val s = mom.signIn(http, { disk })
        val repo = CloudRepository(mom.snapshots, mom.cloudState, s, mom.prefs, config = { TEST_CLOUD }, scope = appScope)
        s.start("Pixel")
        val gate = CompletableDeferred<Unit>().also { http.gate = it }
        val screen = CoroutineScope(Job())
        var finishing: Job? = null
        screen.launch { finishing = repo.finishSignIn("1234567") }.join()
        withTimeout(20_000) { http.entered.await() }
        screen.cancel() // the user left Settings while the code was being exchanged
        gate.complete(Unit)
        withTimeout(20_000) { finishing!!.join() }
        assertEquals(SignInState.Done(CloudEnableResult.ENABLED), s.state.value)
        assertEquals("AT1", mom.cloudSecrets.tokens()!!.accessToken)
    }
}
