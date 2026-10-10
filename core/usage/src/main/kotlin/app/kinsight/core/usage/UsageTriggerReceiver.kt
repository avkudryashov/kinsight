package app.kinsight.core.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Clock or zone changed (both broadcasts are exempt from the implicit-broadcast limits): collect right away, so
 * events before the change keep the old zone and the clock shift is measured close to the moment it happened.
 */
class UsageTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> UsageWork.collectNow(context, force = false)
        }
    }
}
