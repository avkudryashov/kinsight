package app.kinsight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.CloudGraph
import app.kinsight.core.data.CloudWork
import org.junit.Assert.assertEquals
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

    /** R14/R15: the wipe and every cloud run of the process take one lock, or a run could write the old group's keys back. */
    @Test
    fun theRunnerAndTheWipeShareOneRunLock() {
        val g = AppGraph.get(app)
        assertSame(CloudGraph.runLock, g.cloudSecrets.runLock)
        assertSame(g.cloudSecrets.runLock, g.cloud.runLock)
        assertSame(g.cloud.runLock, g.reset.runLock)
    }
}
