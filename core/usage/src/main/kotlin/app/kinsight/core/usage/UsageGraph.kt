package app.kinsight.core.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomUsageQueue

/**
 * Process-wide wiring until Hilt arrives (stage 4): one database, one settings DataStore, one pipeline lock.
 * [targetProvider] stays null until a group exists, so the pipeline only fills the local queue.
 */
object UsageGraph {
    const val SETTINGS_NAME = "kinsight_usage_settings"

    @Volatile
    var targetProvider: suspend () -> PublishTarget? = { null }

    @Volatile
    private var override: UsagePipeline? = null

    @Volatile
    private var instance: UsagePipeline? = null

    fun pipeline(context: Context): UsagePipeline =
        override ?: instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

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
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(SETTINGS_NAME) }),
        )
        return UsagePipeline(
            db = db,
            collector = collector,
            settings = settings,
            apps = PackageAppInfoSource(pm),
            target = { targetProvider() },
        )
    }
}
