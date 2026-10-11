package app.kinsight.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `noBackupFilesDir/kinsight-reset.pending` (audit A-4): written (fsynced) before an identity reset starts, removed
 * when it ends. Found at process start, it means the reset was cut short and must run again: otherwise the old keys
 * could survive over an emptied log, and a re-pair with them (Rewelcome) would reuse seqs the family already holds.
 * Blocking file I/O: callers run it on an IO dispatcher.
 */
class ResetMarker(private val file: File) {
    fun exists(): Boolean = file.exists()

    fun set() {
        FileOutputStream(file).use { out ->
            out.write(1)
            out.fd.sync()
        }
    }

    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
    }

    companion object {
        const val FILE_NAME = "kinsight-reset.pending"
    }
}
