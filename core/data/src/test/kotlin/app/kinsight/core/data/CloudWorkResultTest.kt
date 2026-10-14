package app.kinsight.core.data

import androidx.work.ListenableWorker
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudWorkResultTest {
    @Test
    fun onlyNetworkTroubleIsRetried() {
        assertEquals(ListenableWorker.Result.retry(), CloudWork.resultOf(CloudRun.RETRY))
        for (run in CloudRun.entries - CloudRun.RETRY) assertEquals(ListenableWorker.Result.success(), CloudWork.resultOf(run), run.name)
    }
}
