package app.kinsight.core.usage

import android.content.Intent
import android.content.pm.PackageManager

/** The current default launcher; none when the user has not picked one (the resolver activity "android"). */
class HomeLauncherSource(private val pm: PackageManager) : LauncherSource {
    override fun launchers(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return if (pkg == null || pkg == RESOLVER_PACKAGE) emptySet() else setOf(pkg)
    }

    private companion object {
        const val RESOLVER_PACKAGE = "android"
    }
}
