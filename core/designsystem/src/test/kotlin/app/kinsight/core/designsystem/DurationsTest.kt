package app.kinsight.core.designsystem

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.kinsight.core.designsystem.component.absoluteMoment
import app.kinsight.core.designsystem.component.formatDuration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun boundaries() {
        assertEquals("<1 min", formatDuration(res, 0))
        assertEquals("1 min", formatDuration(res, 60_000))
        assertEquals("59 min", formatDuration(res, 59 * 60_000L + 59_999))
        assertEquals("1 h", formatDuration(res, 60 * 60_000L))
        assertEquals("1 h 1 min", formatDuration(res, 61 * 60_000L))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianFormats() {
        assertEquals("45 мин", formatDuration(res, 45 * 60_000L))
        assertEquals("2 ч 5 мин", formatDuration(res, 125 * 60_000L))
        assertEquals("<1 мин", formatDuration(res, 59_000))
        assertEquals("3 ч", formatDuration(res, 180 * 60_000L))
    }

    @Test
    fun anAbsoluteMomentIsATimeTodayAndADateBefore() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 10, 10).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val today = absoluteMoment(ctx, now - 3_600_000, now)
        assertFalse(today, today.contains("Oct"))
        val before = absoluteMoment(ctx, now - 86_400_000, now)
        assertTrue(before, before.contains("Oct 9"))
    }
}
