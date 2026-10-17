package app.kinsight.core.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.keystore.CloudFiles
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.transport.ble.AndroidPairingLink
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.transport.ble.CloudSync
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.usage.AppOpsUsageAccess
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual wiring for the screens (no Hilt in v1): one Graph per process over the existing singletons. */
object AppGraph {
    @Volatile
    private var graph: Graph? = null

    fun get(context: Context): Graph = graph ?: synchronized(this) {
        graph ?: Graph(context.applicationContext).also { graph = it }
    }
}

class Graph internal constructor(app: Context) {
    private val appContext: Context = app

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

    /** Spec §8: the child's tracking reads and signs through the family; the app installs it in LocationGraph. */
    val familyLocation = FamilyLocation(db, groups, keys, settings, ownAppend, snapshots, upload = { CloudWork.uploadSoon(app) })

    /** The one CloudSecrets of the process (R15); its runLock (CloudGraph.runLock) is the one every cloud run must take. */
    val cloudSecrets = CloudSecrets(
        CloudFiles.vault(app),
        CloudFiles.groupKeys(app),
        CloudFiles.retired(app),
        onAccess = { CloudWork.runSoon(app) },
        runLock = CloudGraph.runLock,
    )
    val cloudState = CloudStateStore.create(app.preferencesDataStoreFile(CloudStateStore.NAME), CoroutineScope(Dispatchers.IO + SupervisorJob()))
    val reset = LocalReset(
        db = db,
        pipeline = pipeline,
        forgetGroupFiles = {
            // Under the cloud runLock (R14), the location wipe runs twice. First, so a failure in it aborts the reset
            // before anything is deleted. Again in `finally`, after the group file is gone and whether or not a cloud step
            // failed: an alert, tick or refresh that re-armed tracking in between does not survive (with no group file
            // the gate stays closed after that).
            LocationGraph.forget(app)
            var failed: Throwable? = null
            try {
                SyncGraph.forgetGroup(app)
                CloudWork.cancel(app)
                cloudSecrets.forget()
                cloudState.clear()
            } catch (e: Throwable) {
                failed = e
                throw e
            } finally {
                try {
                    withContext(NonCancellable) { LocationGraph.forget(app) }
                } catch (e: Exception) {
                    // The first failure is the one the reset reports.
                    failed?.addSuppressed(e) ?: throw e
                }
            }
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
    /**
     * Spec §7.3: the old phone leaves by itself once its transfer invite's new phone holds the owner role. The leave is
     * launched, not joined (final review, Minor 1): the check runs in a snapshot collector, which must not wait out the
     * leave's session; leaveAndForget dedupes a second call while the first runs.
     */
    val ownerTransfer = OwnerTransfer(db, groups, keys, cloudState, leave = { group.leaveAndForget() })
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
        onJoined = { CloudWork.runSoon(app) },
        ownerTransfer = ownerTransfer,
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
        runLock = cloudSecrets.runLock,
    )
    val usage = UsageRepository(snapshots, prefs, appendOwn = { build -> pipeline.appendOwn(build = build) })
    val location = LocationRepository(snapshots, ownAppend)

    /** Spec §10: the family PIN (parents) and the child's settings lock. */
    val childLock = ChildLock(
        snapshots, ownAppend, prefs, onPinSaved = { CloudWork.uploadSoon(app) },
        elapsed = { ElapsedSample(SystemClock.elapsedRealtime(), Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)) },
    )
    /** One sync status for both paths (Task 13): this phone's cloud, for the sync service's notification and the screens. */
    val cloudSync: Flow<CloudSync> = CloudSyncSource(db, groups, keys, settings, cloudState).flow
    val service = ServiceRepository(
        SyncGraph.snapshot, transport, { BlePermissions.missing(app) }, sync, usageAccess = { AppOpsUsageAccess(app).granted() },
        cloud = cloudSync, cloudNow = { CloudWork.opened(app) },
    )
    val pruner = Pruner(db, groups, settings, keys)
    val settingsRepository = SettingsRepository(
        settings, prefs, pipeline, pruner::prune,
        onConsentUpgraded = {
            CloudWork.uploadSoon(app)
            LocationGraph.refresh(app, rearm = true)
        },
    )

    /** The one CloudRunner of the process (R15): the worker and the debug hooks run it; it shares the wipe's lock (R14). */
    val cloud = CloudRunner(
        db, groups, keys, settings, cloudSecrets, cloudState, ownAppend,
        disk = CloudGraph::disk, runLock = cloudSecrets.runLock,
        // The run holds runLock; the Heartbeat takes the transport state lock and the pipeline lock after it (lock order).
        config = { CloudGraph.config }, afterSync = { SyncGraph.emitHeartbeatIfDue(app) },
        // The owner turned the cloud off for the family: no more cloud work, the child's tracking gate closes, and the
        // revoke job waits for the others.
        onTurnedOff = { CloudWork.turnedOff(app, it) },
    )
    val cloudSignIn = CloudSignIn(
        db, groups, keys, ownAppend, cloudSecrets, cloudState,
        config = { CloudGraph.config },
        disk = CloudGraph::disk,
        onEnabled = { CloudWork.runSoon(app) },
    )
    val cloudRepository = CloudRepository(snapshots, cloudState, cloudSignIn, prefs, config = { CloudGraph.config }, scope = scope, secrets = cloudSecrets, cloudOff = cloud::turnOff)

    /**
     * Spec §10 on a child's phone: location access and the system switch (LocationWatch), at every cloud worker run, on
     * the system broadcast, at start and back from the location row. A recorded event is signed and uploaded at once.
     * Never under the cloud runLock (it takes the transport state lock, then the pipeline lock).
     */
    suspend fun checkLocationState() {
        val recorded = SyncGraph.onLocationState(
            appContext,
            familyLocation.watchGate(),
            access = { LocationPermissions.state(appContext) == LocationPermissions.State.ALWAYS },
            enabled = { LocationPermissions.systemEnabled(appContext) },
        )
        LocationStateFollow.after(
            recorded,
            refresh = { LocationGraph.refresh(appContext, rearm = true) },
            publish = { pipeline.collectAndPublish() },
            upload = { CloudWork.uploadSoon(appContext) },
        )
    }

    /** [checkLocationState] on the app scope; a failure is logged by class name. */
    fun launchLocationCheck(): Job = scope.launch {
        try {
            checkLocationState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KinsightLocation", "location state check failed: ${e.javaClass.simpleName}")
        }
    }

    init {
        // Only while a transfer record exists: every membership change is checked (OwnerTransfer.check).
        scope.launch {
            cloudState.state.map { it.transfer != null }.distinctUntilChanged().collectLatest { pending ->
                if (pending) {
                    snapshots.snapshots.collect {
                        try {
                            ownerTransfer.check()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("KinsightOwnerTransfer", "check failed: ${e.javaClass.simpleName}") // the next change retries
                        }
                    }
                }
            }
        }
    }
}
