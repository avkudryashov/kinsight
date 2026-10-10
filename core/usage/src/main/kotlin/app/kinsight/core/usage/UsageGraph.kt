package app.kinsight.core.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.model.proto.StateEvent

/**
 * Process-wide wiring (no Hilt in v1): one database, one settings DataStore, one pipeline lock.
 * [targetProvider] stays null until a group exists; [ownUsageGate] (installed by SyncGraph.install) lets only a
 * consenting CHILD collect and publish its usage, and is closed until then.
 */
object UsageGraph {
    const val SETTINGS_NAME = "kinsight_usage_settings"

    @Volatile
    var targetProvider: suspend () -> PublishTarget? = { null }

    @Volatile
    var ownUsageGate: suspend (PublishTarget?) -> Boolean = { false }

    /**
     * Hears the pipeline's own StateEvents (UsagePipeline.onOwnStateEvent), set by the app at process start
     * (IntegrityFastSync.install, core/data). Runs inside the pipeline lock: it must only hand the kind off.
     */
    @Volatile
    var ownStateEventListener: (StateEvent.Kind) -> Unit = {}

    @Volatile
    private var override: UsagePipeline? = null

    @Volatile
    private var instance: UsagePipeline? = null

    @Volatile
    private var settingsInstance: SettingsStore? = null

    fun pipeline(context: Context): UsagePipeline =
        override ?: instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    /** The one SettingsStore of the process (DataStore allows one active instance per file). */
    fun settings(context: Context): SettingsStore = settingsInstance ?: synchronized(this) {
        settingsInstance ?: SettingsStore(
            PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(SETTINGS_NAME) }),
        ).also { settingsInstance = it }
    }

    @VisibleForTesting
    fun setOverride(pipeline: UsagePipeline?) {
        override = pipeline
    }

    private fun create(context: Context): UsagePipeline {
        val db = KinsightDatabaseFactory.shared(context)
        val pm = context.packageManager
        val collector = Collector(
            queue = RoomUsageQueue(db),
            source = UsageStatsEventSource(context.getSystemService(UsageStatsManager::class.java)),
            access = AppOpsUsageAccess(context),
            clock = AndroidDeviceClock(context),
            launchers = HomeLauncherSource(pm),
        )
        return UsagePipeline(
            db = db,
            collector = collector,
            settings = settings(context),
            apps = PackageAppInfoSource(pm),
            target = { targetProvider() },
            ownUsage = { ownUsageGate(it) },
            onOwnStateEvent = { ownStateEventListener(it) },
        )
    }
}
