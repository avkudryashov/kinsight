package app.kinsight.feature.onboarding

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.usage.UsageAccessSettings
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// core/usage has no Robolectric (its android.jar returns defaults), so the shared helper is pinned here.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageAccessIntentsTest {
    @Test
    fun kinsightsOwnRowThenTheListThenAppInfo() {
        val intents = UsageAccessSettings.intents("app.kinsight")
        assertEquals(
            listOf(Settings.ACTION_USAGE_ACCESS_SETTINGS, Settings.ACTION_USAGE_ACCESS_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            intents.map { it.action },
        )
        assertEquals("package:app.kinsight", intents[0].data.toString())
        assertNull(intents[1].data, "the whole list: no package")
        assertEquals("package:app.kinsight", intents[2].data.toString())
    }
}
