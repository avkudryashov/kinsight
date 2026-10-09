package app.kinsight.core.database

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Just enough Context for Room on the JVM: database names are absolute paths. */
class HostContext : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File = File(name)
    override fun getApplicationContext(): Context = this
}
