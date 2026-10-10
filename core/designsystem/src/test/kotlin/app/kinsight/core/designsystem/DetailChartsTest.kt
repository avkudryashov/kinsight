package app.kinsight.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Column
import app.kinsight.core.designsystem.component.BarChart
import app.kinsight.core.designsystem.component.CategoryLegend
import app.kinsight.core.designsystem.component.Heatmap
import app.kinsight.core.designsystem.component.PeriodHeader
import app.kinsight.core.designsystem.component.StackedBarChart
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DetailChartsTest {
    @get:Rule
    val rule = createComposeRule()

    private val colors = listOf(Color.Red, Color.Blue)

    private fun clickLabel(label: String) = SemanticsMatcher("onClick label = $label") {
        SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == label
    }

    @Test
    fun stackedBarsAreClickableWithALabelEach() {
        var clicked = -1
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                StackedBarChart(
                    stacks = listOf(listOf(1L, 2L), listOf(0L, 0L), listOf(3L)),
                    colors = colors,
                    labels = listOf("M", "T", "W"),
                    summary = "Week total 6 min",
                    onBarClick = { clicked = it },
                    barClickLabel = { "Open day $it" },
                    highlight = 2,
                )
            }
        }
        rule.onNodeWithContentDescription("Week total 6 min").assertExists()
        rule.onNode(clickLabel("Open day 0")).assertExists()
        rule.onNode(clickLabel("Open day 2")).assertExists()
        rule.onNode(clickLabel("Open day 1")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, clicked)
    }

    @Test
    fun barsThatCannotOpenHaveNoClickAction() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                StackedBarChart(
                    stacks = listOf(listOf(1L), listOf(2L), listOf(0L)),
                    colors = colors,
                    labels = listOf("M", "T", "W"),
                    summary = "Week",
                    onBarClick = {},
                    barClickLabel = { "Open day $it" },
                    barClickable = { it < 2 },
                )
            }
        }
        rule.onNode(clickLabel("Open day 1")).assertExists()
        rule.onNode(clickLabel("Open day 2")).assertDoesNotExist()
        rule.onNodeWithContentDescription("Open day 2").assertDoesNotExist()
    }

    @Test
    fun stackedBarsWithoutAClickHandlerAreOnlyTheSummary() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                StackedBarChart(listOf(listOf(1L)), colors, listOf("M"), summary = "One bar", barClickLabel = { "Open $it" })
            }
        }
        rule.onNodeWithContentDescription("One bar").assertExists()
        rule.onNode(clickLabel("Open 0")).assertDoesNotExist()
    }

    @Test
    fun stackedBarsSurviveEmptyNegativeAndMismatchedData() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                StackedBarChart(emptyList(), emptyList(), emptyList(), summary = "Empty")
                StackedBarChart(listOf(emptyList(), listOf(0L)), colors, listOf("M"), summary = "Zeros")
                StackedBarChart(listOf(listOf(-4L, 2L, 5L)), emptyList(), listOf("M", "T"), summary = "Odd", highlight = 9)
            }
        }
        rule.onNodeWithContentDescription("Empty").assertExists()
        rule.onNodeWithContentDescription("Zeros").assertExists()
        rule.onNodeWithContentDescription("Odd").assertExists()
    }

    @Test
    fun heatmapIsReadAsItsSummary() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Heatmap(
                    cells = Array(7) { d -> LongArray(24) { h -> (d * h).toLong() } },
                    rowLabels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
                    summary = "Most use on Sunday evening",
                )
                Heatmap(arrayOf(longArrayOf(-1L)), emptyList(), summary = "Ragged")
            }
        }
        rule.onNodeWithContentDescription("Most use on Sunday evening").assertExists()
        rule.onNodeWithContentDescription("Ragged").assertExists()
        rule.onNodeWithText("Mo").assertDoesNotExist()
    }

    @Test
    fun periodHeaderArrowsAreTaggedLabelledAndLargeEnough() {
        var back = 0
        var forward = 0
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                PeriodHeader("12 – 18 Oct", canBack = true, canForward = false, onBack = { back++ }, onForward = { forward++ })
            }
        }
        rule.onNodeWithTag("det_period_title").assertExists()
        rule.onNodeWithText("12 – 18 Oct").assertExists()
        rule.onNodeWithContentDescription("Previous period").assertExists()
        rule.onNodeWithContentDescription("Next period").assertExists()
        rule.onNodeWithTag("det_prev").assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("det_next").assertIsNotEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("det_prev").performClick()
        rule.onNodeWithTag("det_next").performClick()
        assertEquals(1, back)
        assertEquals(0, forward)
    }

    @Test
    fun legendNamesEveryCategoryInText() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                CategoryLegend(listOf("Games" to Color.Red, "Video" to Color.Blue))
            }
        }
        rule.onNodeWithText("Games").assertExists()
        rule.onNodeWithText("Video").assertExists()
    }

    @Test
    fun aLimitLineIsReadWithTheSummary() {
        rule.setContent {
            KinsightTheme(dynamicColor = false) {
                Column {
                    StackedBarChart(
                        stacks = listOf(listOf(1L), listOf(2L)),
                        colors = colors,
                        labels = listOf("M", "T"),
                        summary = "Week",
                        limit = 3L,
                        limitLabel = "Limit 3 h",
                    )
                    StackedBarChart(
                        stacks = listOf(listOf(1L)),
                        colors = colors,
                        labels = listOf("M"),
                        summary = "Clickable",
                        onBarClick = {},
                        limit = 3L,
                        limitLabel = "Limit 1 h",
                    )
                    BarChart(listOf(1L, 2L), listOf("M", "T"), summary = "Bars", limit = 2L, limitLabel = "Limit 2 h")
                }
            }
        }
        rule.onNodeWithContentDescription("Week. Limit 3 h").assertExists()
        rule.onNodeWithContentDescription("Clickable. Limit 1 h").assertExists()
        rule.onNodeWithContentDescription("Bars. Limit 2 h").assertExists()
    }
}
