package app.kinsight.debug

import app.kinsight.core.data.CloudDebugDisk
import app.kinsight.core.data.CloudDebugEnable
import app.kinsight.core.data.CloudSignIn

/** Debug builds only: the in-memory Disk for emulator runs (roadmap Y2) and the cloud turned on without Yandex. */
object DebugCloud {
    fun fakeDisk(on: Boolean): String {
        CloudDebugDisk.set(on)
        return "fakeDisk=$on"
    }

    /**
     * CLOUD_FAKE_ENABLE: the real turning-on path with a fake token, on an admin parent whose fake Disk is on
     * ([CloudDebugEnable], which also states the limit: one emulator's Disk, the child gets the access over Bluetooth).
     */
    suspend fun fakeEnable(signIn: CloudSignIn): String = "fakeEnable=" + CloudDebugEnable.enable(signIn)
}
