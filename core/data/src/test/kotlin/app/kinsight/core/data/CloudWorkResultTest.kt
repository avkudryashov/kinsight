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
}
