package app.kinsight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.CloudGraph
import app.kinsight.core.data.CloudWork
import app.kinsight.notifications.AlertsWork
import app.kinsight.core.database.wipeAll
import app.kinsight.core.model.storageOwnerEntry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec §9: one cloud job every 15 minutes, only with a network; cancelled when the cloud is off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestKinsightApplication::class)
class CloudWorkTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theCloudRunsEveryQuarterHourWithANetwork() {
        CloudWork.schedule(app)
        val info = WorkManager.getInstance(app).getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(15 * 60_000L, info.periodicityInfo!!.repeatIntervalMillis)
        CloudWork.cancel(app)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    /**
     * Task 14: a run that skipped a pack queues one more run when the pack comes due, with a network; a later run moves it
     * to its own earliest due time; cancelled with the rest.
     */
    @Test
    fun aSkippedPackQueuesAFollowUpRunWhenItIsDue() {
        CloudWork.retrySkipped(app, 600_000L, fromRetry = false)
        val wm = WorkManager.getInstance(app)
        val info = wm.getWorkInfosForUniqueWork(CloudWork.RETRY).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(600_000L, info.initialDelayMillis)
        CloudWork.retrySkipped(app, 300_000L, fromRetry = false)
        val live = wm.getWorkInfosForUniqueWork(CloudWork.RETRY).get().single { it.state != WorkInfo.State.CANCELLED }
        assertEquals("the newest earliest due time wins", 300_000L, live.initialDelayMillis)
        CloudWork.cancel(app)
        assertTrue(wm.getWorkInfosForUniqueWork(CloudWork.RETRY).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    /** Off by default (review Minor 1): without a group with a storage owner no app hook schedules anything. */
    @Test
    fun withoutACloudNothingIsScheduled() = runBlocking<Unit> {
        CloudWork.keepScheduled(app).join()
        CloudWork.runSoon(app).join()
        CloudWork.opened(app).join()
        val wm = WorkManager.getInstance(app)
        assertTrue(wm.getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().isEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(CloudWork.NOW).get().isEmpty())
    }

    /** Rescheduling at every start, session and app open keeps the running job, so the 15-minute cadence is not reset. */
    @Test
    fun reschedulingKeepsThePeriodicJob() {
        val wm = WorkManager.getInstance(app)
        CloudWork.schedule(app)
        val first = wm.getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().single().id
        CloudWork.schedule(app)
        assertEquals(first, wm.getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().single { it.state != WorkInfo.State.CANCELLED }.id)
        CloudWork.cancel(app)
    }

    /** Opening the app replaces a run stuck in a backoff (REPLACE, spec §9). */
    @Test
    fun openingTheAppReplacesAWaitingRun() {
        val wm = WorkManager.getInstance(app)
        fun live() = wm.getWorkInfosForUniqueWork(CloudWork.NOW).get().filter { it.state != WorkInfo.State.CANCELLED }.map { it.id }
        CloudWork.runNow(app, ExistingWorkPolicy.REPLACE)
        val first = live().single()
        CloudWork.runNow(app, ExistingWorkPolicy.REPLACE)
        val replaced = live().single()
        assertNotEquals(first, replaced)
        CloudWork.cancel(app)
    }

    /**
     * Final review Important 1: a token arriving while a run is in flight (it may have read the dead token) gets a run of
     * its own after that one, so a restore or a hand-off never leaves the §11 error on screen until the next period.
     */
    @Test
    fun aTokenArrivingDuringARunQueuesAnotherAfterIt() {
        val wm = WorkManager.getInstance(app)
        fun live() = wm.getWorkInfosForUniqueWork(CloudWork.NOW).get().filter { it.state != WorkInfo.State.CANCELLED }
        CloudWork.runNow(app, ExistingWorkPolicy.REPLACE) // the app was opened: a run is on its way
        val inFlight = live().single()
        CloudWork.afterToken(app)
        val after = live().filter { it.id != inFlight.id }.single()
        assertEquals("chained after the run in flight, not dropped", WorkInfo.State.BLOCKED, after.state)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, CloudWork.TOKEN_POLICY)
        assertEquals(1, wm.getWorkInfosForUniqueWork(CloudWork.PERIODIC).get().count { it.state != WorkInfo.State.CANCELLED })
        CloudWork.cancel(app)
    }

    /**
     * Y4 final review Important 2: a child outside its zones asks for an upload every 5 minutes. Offline the run waits on
     * its network constraint; the ticks never pile more runs behind it (288 is a day of ticks), the waiting one reads all.
     */
    @Test
    fun offlineTicksLeaveAtMostOneQueuedUpload() = runBlocking<Unit> {
        val wm = WorkManager.getInstance(app)
        repeat(288) { CloudWork.upload(app) }
        val pending = wm.getWorkInfosForUniqueWork(CloudWork.NOW).get().filter { !it.state.isFinished }
        assertEquals(1, pending.size)
        assertEquals(WorkInfo.State.ENQUEUED, pending.single().state)
        CloudWork.cancel(app)
    }

    /**
     * Rulings L8 (Y4 Task 7 review Minor 4), on the real app graph: with the cloud on and a cloud run holding the process
     * runLock, the location host's upload (CloudWork.uploadSoon) still checks the cloud and queues its run at once.
     */
    @Test
    fun anUploadIsQueuedWhileACloudRunHoldsTheLock() = runBlocking<Unit> {
        val g = AppGraph.get(app)
        g.group.createGroup("Mom")
        val self = g.keys().deviceId
        assertTrue(g.pipeline.appendOwn { a -> listOf(a.storageOwnerEntry(self, ByteArray(16) { 1 }, 1)) })
        assertTrue("the cloud is on", g.cloud.isOn())
        val wm = WorkManager.getInstance(app)
        g.cloud.runLock.lock()
        try {
            withTimeout(10_000) { CloudWork.uploadSoon(app).join() }
            assertEquals(1, wm.getWorkInfosForUniqueWork(CloudWork.NOW).get().count { !it.state.isFinished })
        } finally {
            g.cloud.runLock.unlock()
            CloudWork.cancel(app)
            // The graph outlives this test (one per process): the next test starts without a group again.
            g.groups.delete()
            g.db.wipeAll()
        }
    }

    /** Y4 final review Important 1: the app hooks a cloud run that brought entries of others to an alerts pass soon. */
    @Test
    fun aRunThatBroughtEntriesAsksForAnAlertsPass() {
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(AlertsWork.SOON).result.get()
        CloudWork.onReceived!!.invoke(app)
        assertTrue(wm.getWorkInfosForUniqueWork(AlertsWork.SOON).get().any { it.state != WorkInfo.State.CANCELLED })
    }

    /** R14/R15: the wipe and every cloud run of the process take one lock, or a run could write the old group's keys back. */
    @Test
    fun theRunnerAndTheWipeShareOneRunLock() {
        val g = AppGraph.get(app)
        assertSame(CloudGraph.runLock, g.cloudSecrets.runLock)
        assertSame(g.cloudSecrets.runLock, g.cloud.runLock)
        assertSame(g.cloud.runLock, g.reset.runLock)
    }
}
