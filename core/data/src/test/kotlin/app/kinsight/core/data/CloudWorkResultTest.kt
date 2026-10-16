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
