package app.kinsight.core.designsystem

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.kinsight.core.designsystem.component.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DurationsTest {
    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun englishFormats() {
        assertEquals("<1 min", formatDuration(res, 59_000))
        assertEquals("45 min", formatDuration(res, 45 * 60_000L))
        assertEquals("3 h", formatDuration(res, 180 * 60_000L))
        assertEquals("2 h 5 min", formatDuration(res, 125 * 60_000L))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianFormats() {
        assertEquals("45 мин", formatDuration(res, 45 * 60_000L))
        assertEquals("2 ч 5 мин", formatDuration(res, 125 * 60_000L))
    }
}
