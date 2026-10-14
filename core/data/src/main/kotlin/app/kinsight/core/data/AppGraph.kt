package app.kinsight.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.keystore.CloudFiles
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.transport.ble.AndroidPairingLink
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.usage.AppOpsUsageAccess
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/** Manual wiring for the screens (no Hilt in v1): one Graph per process over the existing singletons. */
object AppGraph {
    @Volatile
    private var graph: Graph? = null

    fun get(context: Context): Graph = graph ?: synchronized(this) {
        graph ?: Graph(context.applicationContext).also { graph = it }
    }
}

class Graph internal constructor(app: Context) {
    /** App-wide work that must outlive a screen (leaving the group). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = KinsightDatabaseFactory.shared(app)
    val groups = SyncGraph.groups(app)
    val transport = SyncGraph.state(app)
    val pipeline = UsageGraph.pipeline(app)
    val settings = UsageGraph.settings(app)
    val prefs = AppPrefs.create(app.preferencesDataStoreFile(AppPrefs.NAME), CoroutineScope(Dispatchers.IO + SupervisorJob()))
    val keys: suspend () -> DeviceKeys = { DeviceIdentity.get(app).keys }
    val sync: SyncControl = ServiceSyncControl(app)
    val snapshots = Snapshots(db, groups, keys, transport, scope = scope)

    private val ownAppend: OwnAppend = { build -> pipeline.appendOwn(build = build) }

    /** The one CloudSecrets of the process (R15); its runLock (CloudGraph.runLock) is the one every cloud run must take. */
    val cloudSecrets = CloudSecrets(
        CloudFiles.vault(app),
        CloudFiles.groupKeys(app),
        onAccess = { CloudWork.runSoon(app) },
        runLock = CloudGraph.runLock,
    )
    val cloudState = CloudStateStore.create(app.preferencesDataStoreFile(CloudStateStore.NAME), CoroutineScope(Dispatchers.IO + SupervisorJob()))
    val reset = LocalReset(
        db = db,
        pipeline = pipeline,
        forgetGroupFiles = {
            SyncGraph.forgetGroup(app)
            CloudWork.cancel(app)
            cloudSecrets.forget()
            cloudState.clear()
        },
        settings = settings,
        prefs = prefs,
        sync = sync,
        wipeIdentity = { DeviceIdentity.resetBlocking(app) },
        snapshots = snapshots,
        marker = ResetMarker(java.io.File(app.noBackupFilesDir, ResetMarker.FILE_NAME)),
        runLock = cloudSecrets.runLock,
        scope = scope,
    )
    val pairing = PairingCoordinator(
        link = AndroidPairingLink(app),
        db = db,
        groups = groups,
        keys = keys,
        pipeline = pipeline,
        settings = settings,
        reset = reset,
        sync = sync,
        snapshots = snapshots,
        cloud = cloudSecrets,
    )
    val group = GroupRepository(
        snapshots = snapshots,
        db = db,
        groups = groups,
        keys = keys,
        pipeline = pipeline,
        sync = sync,
        scope = scope,
        sessions = SyncGraph.snapshot.map { SessionProgress(it.sync.finishedSessions, it.sync.syncing) },
        forget = { reset.resetIdentity(keepName = true) },
    )
    val usage = UsageRepository(snapshots, prefs, appendOwn = { build -> pipeline.appendOwn(build = build) })
    val service = ServiceRepository(SyncGraph.snapshot, transport, { BlePermissions.missing(app) }, sync, usageAccess = { AppOpsUsageAccess(app).granted() })
    val pruner = Pruner(db, groups, settings, keys)
    val settingsRepository = SettingsRepository(settings, prefs, pipeline, pruner::prune)

    /** The one CloudRunner of the process (R15): the worker and the debug hooks run it; it shares the wipe's lock (R14). */
    val cloud = CloudRunner(
        db, groups, keys, settings, cloudSecrets, cloudState, ownAppend,
        disk = CloudGraph::disk, runLock = cloudSecrets.runLock,
    )
    val cloudSignIn = CloudSignIn(
        db, groups, keys, ownAppend, cloudSecrets,
        config = { CloudGraph.config },
        disk = CloudGraph::disk,
        onEnabled = { CloudWork.runSoon(app) },
    )
    val cloudRepository = CloudRepository(snapshots, cloudState, cloudSignIn, prefs, config = { CloudGraph.config })
}
