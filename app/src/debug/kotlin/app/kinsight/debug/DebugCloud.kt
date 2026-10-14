package app.kinsight.debug

import app.kinsight.core.data.CloudDebugDisk

/** Debug builds only: the in-memory Disk for emulator runs (roadmap Y2). It lives as long as the process. */
object DebugCloud {
    fun fakeDisk(on: Boolean): String {
        CloudDebugDisk.set(on)
        return "fakeDisk=$on"
    }
}
