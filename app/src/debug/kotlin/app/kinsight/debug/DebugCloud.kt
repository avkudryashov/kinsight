package app.kinsight.debug

import app.kinsight.core.data.CloudGraph
import app.kinsight.core.transport.cloud.InMemoryDisk

/** Debug builds only: the in-memory Disk for emulator runs (roadmap Y2). It lives as long as the process. */
object DebugCloud {
    fun fakeDisk(on: Boolean): String {
        CloudGraph.fakeDisk = if (on) CloudGraph.fakeDisk ?: InMemoryDisk() else null
        return "fakeDisk=$on"
    }
}
