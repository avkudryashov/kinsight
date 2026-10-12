package app.kinsight.core.usage

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Usage access as Settings shows it: the app op, or the permission when the op is left at its default.
 * `checkOpNoThrow(String, …)` (API 19) is the non-deprecated name in SDK 36; `unsafeCheckOpNoThrow` is deprecated there.
 */
class AppOpsUsageAccess(private val context: Context) : UsageAccess {
    override fun granted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
