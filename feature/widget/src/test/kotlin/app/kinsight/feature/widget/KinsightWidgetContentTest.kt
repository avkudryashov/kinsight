package app.kinsight.feature.widget

import android.content.Context
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KinsightWidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_760_000_000_000L
    private val links = WidgetLinks()

    private var zone: TimeZone? = null

    @Before
    fun utc() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restore() {
        TimeZone.setDefault(zone)
    }

    private fun kid(i: Int) = ChildRow("c$i".repeat(16), "Kid$i", (60L + i) * 60_000, hasData = true, status = ChildStatus.Updated(now))

    @Test
    fun emptyStateOpensKinsight(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.LIST)
        provideComposable { GlanceTheme { WidgetContent(WidgetState.Empty, now, links) } }
        onNode(hasTestTag("wid_empty")).assertExists()
        onNode(hasText("Open Kinsight")).assertExists()
    }

    @Test
    fun listShowsChildrenAndMore(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.LIST)
        val state = WidgetState.Parent((1..4).map(::kid), more = 2)
        provideComposable { GlanceTheme { WidgetContent(state, now, links) } }
        onNode(hasTestTag("wid_child_${kid(1).id}")).assertExists()
        onNode(hasTestTag("wid_child_${kid(4).id}")).assertExists()
        onNode(hasText("+2 more children")).assertExists()
    }

    @Test
    fun compactShowsFirstChildOnly(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.COMPACT)
        val state = WidgetState.Parent((1..2).map(::kid), more = 0)
        provideComposable { GlanceTheme { WidgetContent(state, now, links) } }
        onNode(hasTestTag("wid_child_${kid(1).id}")).assertExists()
        onNode(hasTestTag("wid_child_${kid(2).id}")).assertDoesNotExist()
    }

    @Test
    fun childSeesOwnTotalAndParents(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.LIST)
        provideComposable { GlanceTheme { WidgetContent(WidgetState.Child(75 * 60_000, true, listOf("Mom", "Dad")), now, links) } }
        onNode(hasTestTag("wid_self_total")).assertExists()
        onNode(hasText("Visible to: Mom, Dad")).assertExists()
    }

    @Test
    fun compactChildHidesParents(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.COMPACT)
        provideComposable { GlanceTheme { WidgetContent(WidgetState.Child(75 * 60_000, true, listOf("Mom")), now, links) } }
        onNode(hasTestTag("wid_self_total")).assertExists()
        onNode(hasTestTag("wid_parents")).assertDoesNotExist()
    }

    /** now is 2025-10-09 08:53 UTC: earlier the same day shows only the time, another day the date too. */
    @Test
    fun statusTimeIsClockTodayAndDateBefore() {
        val today = statusText(context, ChildStatus.Updated(now - 3_600_000), now)
        assertTrue(today, today.startsWith("Data as of "))
        assertFalse(today, today.contains("Oct"))
        val before = statusText(context, ChildStatus.Updated(now - 86_400_000), now)
        assertTrue(before, before.contains("Oct 8") || before.contains("8 Oct"))
    }

    @Test
    fun statusWords() {
        assertTrue(statusText(context, ChildStatus.Paused(now - 60_000), now).startsWith("Paused since "))
        val noContact = statusText(context, ChildStatus.NoContact(now - 2 * 86_400_000), now)
        assertTrue(noContact, noContact.startsWith("No contact since ") && noContact.contains("Oct 7"))
        assertEquals("Sharing is off", statusText(context, ChildStatus.NotSharing, now))
        assertEquals("No data yet", statusText(context, ChildStatus.NoData, now))
    }

    /**
     * Final fix item 8: the 2×1 widget is 40 dp high at its minimum; the compact parent row (two text lines, no 48 dp
     * spacer) with the compact padding fits in it at the default font, while list rows keep their 48 dp target.
     */
    @Test
    fun theCompactRowFitsTheMinimumHeightAndListRowsKeep48Dp() {
        val padding = 2 * WidgetMetrics.verticalPadding(compact = true).value
        val lines = WidgetMetrics.lineHeightDp(WidgetMetrics.COMPACT_NAME_SP) + WidgetMetrics.lineHeightDp(WidgetMetrics.COMPACT_STATUS_SP)
        val needed = padding + maxOf(WidgetMetrics.rowMinHeight(compact = true).value, lines)
        assertTrue("compact needs $needed dp", needed <= KinsightWidget.COMPACT.height.value)
        assertEquals(48f, WidgetMetrics.rowMinHeight(compact = false).value)
    }

    /** Re-review item 3: the 2×1 empty and no-children states are one 13 sp line, so they fit 40 dp as well. */
    @Test
    fun compactEmptyAndNoChildrenAreOneLine(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.COMPACT)
        provideComposable {
            GlanceTheme {
                WidgetContent(WidgetState.Empty, now, links)
                WidgetContent(WidgetState.Parent(emptyList(), more = 0), now, links)
            }
        }
        onNode(hasTestTag("wid_empty")).assertExists()
        onNode(hasTestTag("wid_no_children")).assertExists()
        onNode(hasTestTag("wid_open")).assertDoesNotExist()
        val needed = 2 * WidgetMetrics.verticalPadding(compact = true).value + WidgetMetrics.lineHeightDp(WidgetMetrics.COMPACT_LINE_SP)
        assertTrue("compact line needs $needed dp", needed <= KinsightWidget.COMPACT.height.value)
    }

    @Test
    fun compactParentRowStillShowsTheChild(): Unit = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(KinsightWidget.COMPACT)
        provideComposable { GlanceTheme { WidgetContent(WidgetState.Parent(listOf(kid(1)), more = 0), now, links) } }
        onNode(hasText("Kid1")).assertExists()
    }
}
