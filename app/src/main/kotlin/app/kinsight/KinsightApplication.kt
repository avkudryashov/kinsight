package app.kinsight

import android.app.Application
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.usage.UsageWork
import app.kinsight.notifications.AlertChannels
import app.kinsight.notifications.AlertsWork

/** Keeps the hourly usage collection scheduled and lets the pipeline sign entries once a group exists (stage 3c). */
class KinsightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncGraph.install(this)
        UsageWork.schedule(this)
        AlertChannels.ensure(this)
        AlertsWork.schedule(this)
        SyncGraph.onSessionFinished = { AlertsWork.runSoon(it) }
        UsageAccessWatcher.register(this)
    }
}
