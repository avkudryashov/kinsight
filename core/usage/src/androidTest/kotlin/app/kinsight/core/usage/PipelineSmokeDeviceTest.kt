package app.kinsight.core.usage

import android.app.usage.UsageStatsManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.kinsight.core.database.KinsightDatabase
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.proto.StateEvent
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PipelineSmokeDeviceTest {
    private val context = grantUsageAccess()
    private val dbName = "kinsight-usage-smoke.db"
    private val settingsFile = File(context.filesDir, "kinsight-usage-smoke.preferences_pb")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings = SettingsStore.create(settingsFile, scope)
    private val keys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(keys, "phone")
    private val target = PublishTarget(genesis.groupId, keys)

    private fun pipeline(db: KinsightDatabase): UsagePipeline {
        val pm = context.packageManager
        return UsagePipeline(
            db = db,
            collector = Collector(
                RoomUsageQueue(db),
                UsageStatsEventSource(context.getSystemService(UsageStatsManager::class.java)),
                AppOpsUsageAccess(context),
                AndroidDeviceClock(context),
                HomeLauncherSource(pm),
            ),
            settings = settings,
            apps = PackageAppInfoSource(pm),
            target = { target },
        )
    }

    @Before
    fun clean() {
        context.deleteDatabase(dbName)
        settingsFile.delete()
    }

    @After
    fun cleanUp() {
        UsageGraph.setOverride(null)
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        context.deleteDatabase(dbName)
        settingsFile.delete()
    }

    @Test
    fun fullPipelineWritesSignedEntriesAndSurvivesRestart(): Unit = runBlocking {
        val db = KinsightDatabaseFactory.open(context, dbName)
        val store = RoomEntryStore(db, genesis.groupId)
        store.applyBatch(listOf(genesis.entry))
        val p = pipeline(db)
        p.setSharing(true) // SHARING_RESUMED, then the first-run backlog (7 days)
        val report = p.collectAndPublish(force = true)
        assertEquals(CollectResult.COLLECTED, report.collect)

        val own = store.liveEntries().filter { it.author == keys.deviceId && it.seq > 1 }
        assertTrue(own.all { it.verifySignature(keys.sign.publicKey) })
        assertTrue(own.any { it.body.state_event?.kind == StateEvent.Kind.SHARING_RESUMED })
        assertTrue(own.any { it.kind == EntryKind.USAGE_SUMMARY }, "no UsageSummary from 7 days of history")
        assertTrue(own.any { it.kind == EntryKind.USAGE_DETAIL })
        for (cat in own.filter { it.kind == EntryKind.APP_CATALOG }) {
            val icon = cat.body.app_catalog!!.icon_webp.toByteArray()
            val bitmap = assertNotNull(BitmapFactory.decodeByteArray(icon, 0, icon.size))
            assertEquals(IconEncoder.SIZE_PX, bitmap.width)
        }
        val last = store.authorState(keys.deviceId).lastSeq
        assertEquals(last, store.versionVector()[keys.deviceId])
        assertEquals(last, own.maxOf { it.seq })
        db.close()

        // Restart: a new database handle and pipeline continue right after the last seq.
        val again = KinsightDatabaseFactory.open(context, dbName)
        pipeline(again).setSharing(false)
        val paused = RoomEntryStore(again, genesis.groupId).liveEntries().single { it.seq == last + 1 }
        assertEquals(StateEvent.Kind.SHARING_PAUSED, paused.body.state_event!!.kind)
        again.close()
    }

    @Test
    fun workerRunsThePipeline(): Unit = runBlocking {
        val db = KinsightDatabaseFactory.open(context, dbName)
        RoomEntryStore(db, genesis.groupId).applyBatch(listOf(genesis.entry))
        UsageGraph.setOverride(pipeline(db))
        val worker = TestListenableWorkerBuilder<UsageWorker>(context)
            .setInputData(workDataOf(UsageWork.KEY_FORCE to true))
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(RoomUsageQueue(db).state() != null, "the worker did not collect")
        db.close()
    }
}
