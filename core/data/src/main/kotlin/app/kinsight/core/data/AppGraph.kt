package app.kinsight.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.transport.ble.AndroidPairingLink
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.SyncGraph
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
    /** App-wide work that must outlive a screen (leaving the group, preflight C5). */
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
    val reset = LocalReset(
        db = db,
        pipeline = pipeline,
        forgetGroupFiles = { SyncGraph.forgetGroup(app) },
        settings = settings,
        prefs = prefs,
        sync = sync,
        wipeIdentity = { DeviceIdentity.resetBlocking(app) },
        snapshots = snapshots,
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
        forget = reset::forgetGroup,
    )
    val usage = UsageRepository(snapshots, prefs, appendOwn = { build -> pipeline.appendOwn(build = build) })
    val service = ServiceRepository(SyncGraph.snapshot, transport, { BlePermissions.missing(app) }, sync)
    val pruner = Pruner(db, groups, settings, keys)
    val settingsRepository = SettingsRepository(settings, prefs, pipeline, pruner::prune)
}
