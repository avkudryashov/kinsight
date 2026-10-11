package app.kinsight.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The app-wide reset status (stage 4 Task 18 fix round 1): one wipe at a time, its outcome outlives any screen. */
class LocalResetStatusTest {
    private val d = TestDevice()
    private val gate = CompletableDeferred<Unit>()
    private val stops = AtomicInteger()
    private var failFiles: Exception? = null
    private val wipes = AtomicInteger()

    private val gatedSync = object : SyncControl {
        override fun start(fast: Boolean) = Unit
        override fun startByUser() = Unit
        override fun requestStop() = Unit
        override suspend fun stopAndWait() {
            stops.incrementAndGet()
            gate.await()
        }
    }

    private val reset = LocalReset(
        db = d.db,
        pipeline = d.pipeline,
        forgetGroupFiles = { failFiles?.let { throw it } ?: d.groups.delete() },
        settings = d.settings,
        prefs = d.prefs,
        sync = gatedSync,
        wipeIdentity = { wipes.incrementAndGet() },
        snapshots = d.snapshots,
        marker = d.resetMarker,
        scope = d.scope,
    )

    @AfterTest
    fun cleanUp() = d.close()

    @Test
    fun aSecondLaunchWhileRunningJoinsTheFirst() = runBlocking<Unit> {
        val first = reset.launchResetIdentity()
        withTimeout(5_000) { reset.status.first { it == ResetStatus.Running(ResetKind.NEW_IDENTITY) } }
        val second = reset.launchResetIdentity()
        val other = reset.launchForgetGroup()
        assertSame(first, second)
        assertSame(first, other)
        gate.complete(Unit)
        first.join()
        assertEquals(1, stops.get())
        assertEquals(1, wipes.get())
        assertEquals(ResetStatus.Idle, reset.status.value)
    }

    @Test
    fun aFailureStaysVisibleUntilCleared() = runBlocking<Unit> {
        failFiles = IllegalStateException("disk full")
        gate.complete(Unit)
        reset.launchResetIdentity().join()
        assertEquals(ResetStatus.Failed(ResetKind.NEW_IDENTITY, "disk full"), reset.status.value)
        assertEquals(0, wipes.get())
        reset.clearFailure()
        assertEquals(ResetStatus.Failed(ResetKind.NEW_IDENTITY, "disk full"), reset.status.value) // marker pending: retry only
        assertFalse(reset.dismissible)
        failFiles = null
        reset.launchResetIdentity().join() // a retry leads out
        assertEquals(ResetStatus.Idle, reset.status.value)
        failFiles = IllegalStateException("disk full")
        reset.launchForgetGroup().join()
        assertTrue(reset.dismissible) // a failed forget has no marker: it can be dismissed
        reset.clearFailure()
        assertEquals(ResetStatus.Idle, reset.status.value)
    }

    @Test
    fun theSuspendingCallsStillThrowAndReportTheirStatus() = runBlocking<Unit> {
        failFiles = IllegalStateException("no space")
        gate.complete(Unit)
        assertFailsWith<IllegalStateException> { reset.forgetGroup() }
        assertEquals(ResetStatus.Failed(ResetKind.FORGET_GROUP, "no space"), reset.status.value)
        failFiles = null
        reset.forgetGroup()
        assertEquals(ResetStatus.Idle, reset.status.value)
        assertTrue(stops.get() == 2)
    }
}
