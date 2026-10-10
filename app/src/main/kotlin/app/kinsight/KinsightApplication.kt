package app.kinsight

import android.app.Application
import app.kinsight.core.usage.UsageWork

/** Stage 3b: keeps the hourly usage collection scheduled. No group yet, so it only fills the local queue. */
class KinsightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageWork.schedule(this)
    }
}
