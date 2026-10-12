package app.kinsight.core.database

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Just enough Context for Room on the JVM: absolute names are used as is, relative ones land in [dir]. */
class HostContext(private val dir: File? = null) : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File {
        val file = File(name)
        return if (file.isAbsolute || dir == null) file else File(dir, name)
    }

    override fun getApplicationContext(): Context = this
}
