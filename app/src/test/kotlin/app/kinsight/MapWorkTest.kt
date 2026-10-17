package app.kinsight

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.kinsight.core.data.MapWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Offline map spec §7: blocks over any network but roaming, queued once; a daily check from the start. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestKinsightApplication::class)
class MapWorkTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val wm get() = WorkManager.getInstance(app)

    @Test
    fun blocksDownloadOffRoamingAndOnce() {
        MapWork.syncSoon(app).result.get()
        val first = wm.getWorkInfosForUniqueWork(MapWork.SYNC).get().single()
        assertEquals(NetworkType.NOT_ROAMING, first.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        MapWork.syncSoon(app).result.get()
        assertEquals("a second ask keeps the queued run", first.id, wm.getWorkInfosForUniqueWork(MapWork.SYNC).get().single().id)
        MapWork.cancel(app)
        assertTrue(wm.getWorkInfosForUniqueWork(MapWork.SYNC).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    /** Offline map spec §3: a child's phone (or a phone with the cloud off) queues no check when entries come. */
    @Test
    fun aChildsPhoneQueuesNoCheck() = runBlocking {
        assertFalse(MapWork.checkIfWanted(app) { false })
        assertTrue(wm.getWorkInfosForUniqueWork(MapWork.CHECK).get().isEmpty())
        assertTrue(MapWork.checkIfWanted(app) { true })
        assertEquals(1, wm.getWorkInfosForUniqueWork(MapWork.CHECK).get().size)
    }

    /** The start-up wiring: without a group this phone is no parent, so new entries queue no check. */
    @Test
    fun withoutAFamilyTheTriggerQueuesNothing() = runBlocking {
        MapWork.checkSoonIfParent(app).join()
        assertTrue(wm.getWorkInfosForUniqueWork(MapWork.CHECK).get().isEmpty())
    }

    @Test
    fun theStartSchedulesTheDailyCheckWithoutNetwork() {
        val info = wm.getWorkInfosForUniqueWork(MapWork.DAILY).get().single()
        assertEquals(24 * 3_600_000L, info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
    }
}
