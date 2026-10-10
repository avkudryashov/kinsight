package app.kinsight.core.usage

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomUsageQueue
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Scheduling against the WorkManager test implementation: unique names, policies, delays and the rollover chain.
 * Work started by the test WorkManager never finishes ([HeldWorker]), so "running" and "appended" stay observable.
 */
@RunWith(AndroidJUnit4::class)
class UsageWorkDeviceTest {
    private val context = grantUsageAccess()
    private val dbName = "kinsight-usage-work.db"
    private val settingsFile = File(context.filesDir, "kinsight-usage-work.preferences_pb")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var wm: WorkManager

    /** Stays RUNNING forever: the future is never completed. */
    class HeldWorker(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { "held" }
    }

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        settingsFile.delete()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    HeldWorker(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        wm = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        UsageGraph.setOverride(null)
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        context.deleteDatabase(dbName)
        settingsFile.delete()
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()
    private fun pending(name: String): List<WorkInfo> = infos(name).filter { !it.state.isFinished }

    @SuppressLint("RestrictedApi")
    private fun spec(id: UUID) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(id.toString())!!

    /** The delay WorkManager stored lies between the delays computed right after and right before the call. */
    private fun assertRolloverDelay(info: WorkInfo, before: Long, after: Long) {
        assertTrue(info.initialDelayMillis in after..before, "delay ${info.initialDelayMillis} not in $after..$before")
    }

    private fun now() = UsageWork.delayToRollover(System.currentTimeMillis(), ZoneId.systemDefault())

    @Test
    fun scheduleIsIdempotentAndArmsTheRollover(): Unit = runBlocking {
        val before = now()
        UsageWork.schedule(context)
        val after = now()
        val hourly = infos(UsageWork.PERIODIC).single()
        assertTrue(hourly.periodicityInfo!!.repeatIntervalMillis == 3_600_000L)
        val rollover = pending(UsageWork.ROLLOVER).single()
        assertRolloverDelay(rollover, before, after)
        assertTrue(spec(rollover.id).input.getBoolean(UsageWork.KEY_FORCE, false))
        assertTrue(spec(rollover.id).input.getBoolean(UsageWork.KEY_ROLLOVER, false))

        UsageWork.schedule(context) // KEEP: neither the hourly work nor the waiting rollover is replaced
        assertEquals(hourly.id, infos(UsageWork.PERIODIC).single().id)
        assertEquals(rollover.id, infos(UsageWork.ROLLOVER).single().id)
    }

    @Test
    fun collectNowIsExpeditedAndQueuesBehindARunningRun(): Unit = runBlocking {
        UsageWork.collectNow(context, force = false)
        val first = infos(UsageWork.NOW).single()
        assertEquals(WorkInfo.State.RUNNING, first.state)
        assertTrue(spec(first.id).expedited)
        assertEquals(false, spec(first.id).input.getBoolean(UsageWork.KEY_FORCE, true))

        UsageWork.collectNow(context, force = true) // APPEND_OR_REPLACE: appended, the running one is untouched
        val all = infos(UsageWork.NOW)
        assertEquals(2, all.size)
        assertEquals(WorkInfo.State.RUNNING, all.single { it.id == first.id }.state)
        val second = all.single { it.id != first.id }
        assertEquals(WorkInfo.State.BLOCKED, second.state)
        assertTrue(spec(second.id).expedited)
        assertTrue(spec(second.id).input.getBoolean(UsageWork.KEY_FORCE, false))
    }

    @Test
    fun zoneChangeCollectsNowAndRecomputesTheRollover(): Unit = runBlocking {
        // A rollover computed in another zone, as if armed before the zone changed.
        val stale = ZoneId.of(if (ZoneId.systemDefault().id == "Pacific/Kiritimati") "Pacific/Pago_Pago" else "Pacific/Kiritimati")
        UsageWork.scheduleRollover(context, zone = stale)
        val old = pending(UsageWork.ROLLOVER).single()

        val before = now()
        UsageTriggerReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val after = now()

        assertEquals(1, infos(UsageWork.NOW).size)
        // REPLACE cancels the stale rollover; WorkManager may already have pruned it from the unique name.
        assertTrue(wm.getWorkInfoById(old.id).get()?.state.let { it == null || it == WorkInfo.State.CANCELLED })
        val fresh = pending(UsageWork.ROLLOVER).single()
        assertNotEquals(old.id, fresh.id)
        assertRolloverDelay(fresh, before, after)
    }

    @Test
    fun timeSetAlsoReplacesTheRollover(): Unit = runBlocking {
        UsageWork.scheduleRollover(context, nowMs = System.currentTimeMillis() - 3 * 3_600_000L)
        val old = pending(UsageWork.ROLLOVER).single()
        UsageTriggerReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals(1, infos(UsageWork.NOW).size)
        assertNotEquals(old.id, pending(UsageWork.ROLLOVER).single().id)
    }

    @Test
    fun rolloverRunArmsTheNextRolloverAndOtherRunsKeepIt(): Unit = runBlocking {
        val db = KinsightDatabaseFactory.open(context, dbName)
        val pm = context.packageManager
        UsageGraph.setOverride(
            UsagePipeline(
                db = db,
                collector = Collector(
                    RoomUsageQueue(db),
                    UsageStatsEventSource(context.getSystemService(UsageStatsManager::class.java)),
                    AppOpsUsageAccess(context),
                    AndroidDeviceClock(context),
                    HomeLauncherSource(pm),
                ),
                settings = SettingsStore.create(settingsFile, scope),
                apps = PackageAppInfoSource(pm),
                target = { null },
            ),
        )
        UsageWork.scheduleRollover(context)
        val current = pending(UsageWork.ROLLOVER).single() // stands in for the rollover run in progress

        // An ordinary run leaves the waiting rollover alone (KEEP).
        val ordinary = TestListenableWorkerBuilder<UsageWorker>(context)
            .setInputData(workDataOf(UsageWork.KEY_FORCE to true))
            .build()
        assertEquals(ListenableWorker.Result.success(), ordinary.doWork())
        assertEquals(current.id, infos(UsageWork.ROLLOVER).single().id)

        // The rollover run appends the next rollover behind itself, a day ahead in the current zone.
        val before = now()
        val rolloverRun = TestListenableWorkerBuilder<UsageWorker>(context)
            .setInputData(workDataOf(UsageWork.KEY_FORCE to true, UsageWork.KEY_ROLLOVER to true))
            .build()
        assertEquals(ListenableWorker.Result.success(), rolloverRun.doWork())
        val after = now()
        val next = infos(UsageWork.ROLLOVER).single { it.id != current.id }
        assertEquals(WorkInfo.State.BLOCKED, next.state)
        assertEquals(current.state, infos(UsageWork.ROLLOVER).single { it.id == current.id }.state)
        assertRolloverDelay(next, before, after)
        assertTrue(spec(next.id).input.getBoolean(UsageWork.KEY_ROLLOVER, false))
        db.close()
    }
}
