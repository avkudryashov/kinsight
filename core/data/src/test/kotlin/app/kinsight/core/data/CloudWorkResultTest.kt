package app.kinsight.core.data

import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudWorkResultTest {
    @Test
    fun onlyNetworkTroubleIsRetried() {
        assertEquals(ListenableWorker.Result.retry(), CloudWork.resultOf(CloudRun.RETRY))
        for (run in CloudRun.entries - CloudRun.RETRY) assertEquals(ListenableWorker.Result.success(), CloudWork.resultOf(run), run.name)
    }

    /** Rulings Q1: an unexpected failure is retried, so an APPEND_OR_REPLACE follow-up queued behind it is not dropped. */
    @Test
    fun anUnexpectedFailureIsRetriedAndACancelIsNot() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.retry(), CloudWork.outcome { throw IOException("boom") })
        assertEquals(ListenableWorker.Result.retry(), CloudWork.outcome { throw IllegalStateException("boom") })
        assertEquals(ListenableWorker.Result.success(), CloudWork.outcome { CloudRun.SYNCED })
        assertFailsWith<CancellationException> { CloudWork.outcome { throw CancellationException("stopped") } }
    }

    /**
     * Parked Y2 minor: the debug in-memory Disk is no public switch of main code. The field is internal (its JVM accessors
     * carry the module suffix), and its only setter, CloudDebugDisk, lives in the debug source set.
     */
    @Test
    fun theFakeDiskHasNoPublicSwitch() {
        val names = CloudGraph::class.java.methods.map { it.name }
        assertEquals(emptyList<String>(), names.filter { it == "setFakeDisk" || it == "getFakeDisk" })
        val main = java.io.File("src/main/kotlin/app/kinsight/core/data/CloudDebugDisk.kt")
        val debug = java.io.File("src/debug/kotlin/app/kinsight/core/data/CloudDebugDisk.kt")
        assertEquals(false to true, main.exists() to debug.exists())
    }

    /** Parked Y2 minor: opening the app replaces a stuck or old run now, never one that started under 30 s ago. */
    @Test
    fun theAppOpenLeavesAFreshRunAlone() = runBlocking<Unit> {
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, CloudWork.openedPolicy(null, 100_000))
        assertEquals(null, CloudWork.openedPolicy(100_000, 100_000))
        assertEquals(null, CloudWork.openedPolicy(100_000, 129_999))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, CloudWork.openedPolicy(100_000, 130_000))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, CloudWork.openedPolicy(100_000, 99_000), "a clock that went back")
        var seen: Long? = null
        assertEquals(CloudRun.SYNCED, CloudWork.nowRun({ 42L }) { seen = CloudWork.nowRunStartedMs; CloudRun.SYNCED })
        assertEquals(42L, seen)
        assertEquals(null, CloudWork.nowRunStartedMs, "cleared when the run ends")
        assertFailsWith<IOException> { CloudWork.nowRun({ 42L }) { throw IOException("boom") } }
        assertEquals(null, CloudWork.nowRunStartedMs, "and when it fails")
    }

    /** Spec §9: the follow-up run queues its successor behind itself; any other run keeps a waiting one. */
    @Test
    fun aFollowUpChainsItselfAndOtherRunsKeepIt() {
        assertEquals(androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, CloudWork.followPolicy(fromFollow = true))
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, CloudWork.followPolicy(fromFollow = false))
        assertEquals(5L, CloudWork.FOLLOW_MINUTES)
    }

    /** Spec §9: only a run that read the Disk asks whether to follow; cloud off, retry or skip end the chain. */
    @Test
    fun onlyASyncedRunFollowsUp() = runBlocking<Unit> {
        for (run in CloudRun.entries - CloudRun.SYNCED) {
            assertEquals(false, CloudWork.followNext(run) { error("asked after $run") }, run.name)
        }
        assertEquals(true, CloudWork.followNext(CloudRun.SYNCED) { true })
        assertEquals(false, CloudWork.followNext(CloudRun.SYNCED) { false })
    }

    /**
     * Y4 final review Important 2: an upload keeps a run that has not started (it reads everything signed by then) and
     * queues one run of its own only behind a run in flight, so offline ticks never pile up runs.
     */
    @Test
    fun anUploadQueuesAtMostOneRunBehindTheOneInFlight() {
        val s = androidx.work.WorkInfo.State.entries.associateBy { it.name }
        assertEquals(CloudWork.UPLOAD_POLICY, CloudWork.uploadPolicy(emptyList()))
        assertEquals(CloudWork.UPLOAD_POLICY, CloudWork.uploadPolicy(listOf(s.getValue("SUCCEEDED"), s.getValue("CANCELLED"))))
        assertEquals(CloudWork.UPLOAD_POLICY, CloudWork.uploadPolicy(listOf(s.getValue("RUNNING"))))
        assertEquals(null, CloudWork.uploadPolicy(listOf(s.getValue("ENQUEUED"))))
        assertEquals(null, CloudWork.uploadPolicy(listOf(s.getValue("RUNNING"), s.getValue("BLOCKED"))))
        assertEquals(null, CloudWork.uploadPolicy(listOf(s.getValue("FAILED"), s.getValue("ENQUEUED"))))
    }

    /** Y4 final review Important 1: a run that brought entries of others asks for an alerts pass (a zone exit, spec §9). */
    @Test
    fun onlyARunThatReceivedEntriesAsksForAlerts() {
        assertEquals(true, CloudWork.alertsNeeded(CloudRunOutcome(CloudRun.SYNCED, received = 3)))
        assertEquals(false, CloudWork.alertsNeeded(CloudRunOutcome(CloudRun.SYNCED, received = 0)))
        for (run in CloudRun.entries - CloudRun.SYNCED) assertEquals(false, CloudWork.alertsNeeded(CloudRunOutcome(run, received = 0)), run.name)
        // A full Disk still reads the others: what it brought gets its alerts too.
        assertEquals(true, CloudWork.alertsNeeded(CloudRunOutcome(CloudRun.STORAGE_FULL, received = 2)))
    }
}
