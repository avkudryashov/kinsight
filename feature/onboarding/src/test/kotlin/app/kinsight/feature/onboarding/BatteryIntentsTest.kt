package app.kinsight.feature.onboarding

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BatteryIntentsTest {
    @Test
    fun appInfoComesBeforeTheAllAppsBatteryList() {
        val intents = batterySettingsIntents("app.kinsight")
        assertEquals(
            listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            intents.map { it.action },
        )
        assertEquals("package:app.kinsight", intents[0].data.toString())
    }
}
