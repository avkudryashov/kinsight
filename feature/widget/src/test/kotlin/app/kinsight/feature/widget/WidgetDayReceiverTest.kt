package app.kinsight.feature.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Final fix item 2: DATE_CHANGED is not delivered to manifest receivers on API 26+, so the manifest does not ask for
 * it (the hourly alert run is the midnight guarantee); clock, zone and language changes redraw the widget.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetDayReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun receivers(action: String): List<String> =
        context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0).map { it.activityInfo.name }

    @Test
    fun theManifestListensToClockZoneAndLocaleButNotDateChanged() {
        val day = WidgetDayReceiver::class.java.name
        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)) {
            assertTrue(action, day in receivers(action))
        }
        assertFalse(day in receivers(Intent.ACTION_DATE_CHANGED))
    }

    @Test
    fun theReceiverHandlesExactlyTheManifestActions() {
        assertEquals(
            setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED),
            WidgetDayReceiver.ACTIONS,
        )
    }
}
