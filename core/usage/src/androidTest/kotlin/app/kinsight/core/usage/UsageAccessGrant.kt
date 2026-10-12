package app.kinsight.core.usage

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants usage access to the test package the way `adb shell appops set <pkg> GET_USAGE_STATS allow` does: the
 * instrumentation runs the command as the shell user. Done in-test because connectedAndroidTest reinstalls the APK.
 */
fun grantUsageAccess(): Context {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val pfd = instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} GET_USAGE_STATS allow")
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // wait for the command to finish
    return context
}
