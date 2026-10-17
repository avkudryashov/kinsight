package app.kinsight

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emulator finding: background location given back in the system settings restarts nothing and broadcasts nothing, so
 * the app looks again whenever it comes to the foreground: the first of its activities started after none was (back from
 * the system settings above all). [onForeground] only launches work. Registers once.
 */
object ForegroundCheck {
    private val registered = AtomicBoolean(false)

    fun register(app: Application, onForeground: (Context) -> Unit) {
        if (registered.compareAndSet(false, true)) app.registerActivityLifecycleCallbacks(Watch(onForeground))
    }

    /** Counts started activities on the main thread; 0 to 1 is the app coming to the foreground. */
    class Watch(private val onForeground: (Context) -> Unit) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) onForeground(activity.applicationContext)
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
