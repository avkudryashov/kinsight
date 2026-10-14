package app.kinsight.core.data

import app.kinsight.core.transport.cloud.InMemoryDisk

/** Debug builds only: the switch for CloudGraph's in-memory Disk (CLOUD_FAKE_DISK). Release builds do not have it. */
object CloudDebugDisk {
    /** The in-memory Disk, or null for Yandex. Switching on twice keeps the same Disk and its files. */
    val current: InMemoryDisk? get() = CloudGraph.fakeDisk

    fun set(on: Boolean) {
        CloudGraph.fakeDisk = if (on) CloudGraph.fakeDisk ?: InMemoryDisk() else null
    }
}
