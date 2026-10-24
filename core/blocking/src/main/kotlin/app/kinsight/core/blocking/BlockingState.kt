package app.kinsight.core.blocking

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Whether the user turned Kinsight's accessibility service on (spec §5.4: the child setup and BlockingWatch read it). */
object BlockingState {
    fun serviceEnabled(context: Context): Boolean {
        val ours = ComponentName(context, BlockingAccessibilityService::class.java)
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == ours
        }
    }
}
