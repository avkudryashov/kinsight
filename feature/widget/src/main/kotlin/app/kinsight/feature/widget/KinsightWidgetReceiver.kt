package app.kinsight.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The AppWidget provider; exported, as the launcher's AppWidget host must reach it. */
class KinsightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KinsightWidget()
}

/**
 * The day change (spec §9.7): at midnight or after the clock or zone moved, «today» is another date. Not exported:
 * only the system sends these broadcasts.
 *
 * Best effort: whether DATE_CHANGED reaches a manifest receiver on every phone is not guaranteed. The guarantee is
 * the hourly alert run (AlertsWorker), which redraws the widget within an hour of midnight; there is deliberately no
 * exact alarm for it.
 */
class WidgetDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                KinsightWidget.update(app)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
