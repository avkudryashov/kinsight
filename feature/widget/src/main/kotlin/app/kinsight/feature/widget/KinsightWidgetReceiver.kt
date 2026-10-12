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
 * The clock or zone moved (spec §9.7): «today» may be another date; the language changed: the widget's words and
 * formats change. Not exported: only the system sends these broadcasts.
 *
 * No DATE_CHANGED: Android 8+ does not deliver it to manifest receivers. Midnight is guaranteed by the hourly alert
 * run (AlertsWorker), which redraws the widget within an hour of it; there is deliberately no exact alarm for it.
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

    internal companion object {
        val ACTIONS = setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
