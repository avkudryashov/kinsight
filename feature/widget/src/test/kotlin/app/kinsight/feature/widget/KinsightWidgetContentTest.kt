package app.kinsight.feature.widget

import android.content.Context
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KinsightWidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_760_000_000_000L
    private val links = WidgetLinks()

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
}
