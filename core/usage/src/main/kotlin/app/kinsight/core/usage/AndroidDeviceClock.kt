package app.kinsight.core.usage

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.ZoneId

class AndroidDeviceClock(private val context: Context) : DeviceClock {
    override fun sample(): ClockSample {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boots = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        return ClockSample(wall, elapsed, boots)
    }

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
