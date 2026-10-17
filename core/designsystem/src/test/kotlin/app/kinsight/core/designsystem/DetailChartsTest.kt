package app.kinsight.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.rememberTextMeasurer
import app.kinsight.core.designsystem.component.ChartAverageLine
import app.kinsight.core.designsystem.component.ChartLimitLine
import app.kinsight.core.designsystem.component.ChartScale
import app.kinsight.core.designsystem.component.gridTicks
import app.kinsight.core.designsystem.component.tickText
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.GraphicsMode

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
    fun weekBarsAreNodesWithTheirValueAndOneActionLabel() {
        var clicked = -1
        rule.setContent {
            KinsightTheme() {
                StackedBarChart(
                    stacks = listOf(listOf(1L, 2L), listOf(0L, 0L), listOf(3L)),
                    colors = colors,
                    labels = listOf("M", "T", "W"),
                    summary = "Week total 6 min",
                    onBarClick = { clicked = it },
                    barDescription = { "Day $it: $it min" },
                    barActionLabel = "Open day",
                    highlight = 2,
                )
            }
        }
        rule.onNodeWithContentDescription("Week total 6 min").assertExists()
        rule.onNode(hasContentDescription("Day 0: 0 min") and clickLabel("Open day")).assertExists()
        rule.onNode(hasContentDescription("Day 1: 1 min") and clickLabel("Open day")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, clicked)
    }

    @Test
    fun barsThatCannotOpenHaveNoClickAction() {
        rule.setContent {
            KinsightTheme() {
                StackedBarChart(
                    stacks = listOf(listOf(1L), listOf(2L), listOf(0L)),
                    colors = colors,
                    labels = listOf("M", "T", "W"),
                    summary = "Week",
                    onBarClick = {},
                    barDescription = { "Day $it" },
                    barActionLabel = "Open day",
                    barClickable = { it < 2 },
                )
            }
        }
        rule.onNode(hasContentDescription("Day 1") and hasClickAction()).assertExists()
        rule.onNode(hasContentDescription("Day 2") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun stackedBarsWithoutAClickHandlerAreOnlyTheSummary() {
        rule.setContent {
            KinsightTheme() {
                StackedBarChart(listOf(listOf(1L)), colors, listOf("M"), summary = "One bar", barDescription = { "Bar $it" })
            }
        }
        rule.onNodeWithContentDescription("One bar").assertExists()
        rule.onNode(hasContentDescription("Bar 0")).assertDoesNotExist()
    }

    private fun showMonth(onBar: (Int) -> Unit) = rule.setContent {
        KinsightTheme() {
            StackedBarChart(
                stacks = List(31) { listOf(60_000L) },
                colors = colors,
                labels = List(31) { "" },
                summary = "Month",
                modifier = Modifier.width(310.dp).testTag("chart"),
                onBarClick = onBar,
                barDescription = { "Day $it: 1 min" },
                barActionLabel = "Open day",
                barClickable = { it < 28 },
                scrub = true,
            )
        }
    }

    @Test
    fun aMonthTapSelectsTheNearestBarAndItsTooltipOpensTheDay() {
        var opened = -1
        showMonth { opened = it }
        rule.onNodeWithTag("chart_tooltip").assertDoesNotExist()
        rule.onNodeWithTag("chart").performTouchInput { click(Offset(width * 10.5f / 31, 40.dp.toPx())) }
        assertEquals(-1, opened) // a tap on a 10 dp bar only selects it
        rule.onNodeWithTag("chart_tooltip").assertTextContains("Day 10: 1 min").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(10, opened)
    }

    @Test
    fun aMonthDragMovesTheSelectionAndAFutureDayIsNotOffered() {
        showMonth {}
        rule.onNodeWithTag("chart").performTouchInput { swipe(Offset(width * 2.5f / 31, 40.dp.toPx()), Offset(width * 20.5f / 31, 40.dp.toPx())) }
        rule.onNodeWithTag("chart_tooltip").assertTextContains("Day 20: 1 min")
        rule.onNodeWithTag("chart").performTouchInput { click(Offset(width * 29.5f / 31, 40.dp.toPx())) }
        rule.onNodeWithTag("chart_tooltip").assertDoesNotExist()
    }

    @Test
    fun theSelectedMonthBarIsMarked() {
        showMonth {}
        rule.onNodeWithTag("chart").performTouchInput { click(Offset(width * 10.5f / 31, 40.dp.toPx())) }
        rule.onNodeWithContentDescription("Day 10: 1 min").assertIsSelected()
        rule.onNodeWithContentDescription("Day 3: 1 min").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun aNewSelectionKeyClearsTheSelection() {
        val key = mutableStateOf(1)
        rule.setContent {
            KinsightTheme() {
                StackedBarChart(
                    stacks = List(31) { listOf(60_000L) },
                    colors = colors,
                    labels = List(31) { "" },
                    summary = "Month",
                    modifier = Modifier.width(310.dp).testTag("chart"),
                    onBarClick = {},
                    barDescription = { "Day $it" },
                    scrub = true,
                    selectionKey = key.value,
                )
            }
        }
        rule.onNodeWithTag("chart").performTouchInput { click(Offset(width * 10.5f / 31, 40.dp.toPx())) }
        rule.onNodeWithTag("chart_tooltip").assertExists()
        key.value = 2
        rule.onNodeWithTag("chart_tooltip").assertDoesNotExist()
    }

    @Test
    fun monthBarsStayReachableWithTalkBack() {
        var opened = -1
        showMonth { opened = it }
        rule.onNode(hasContentDescription("Day 3: 1 min") and clickLabel("Open day")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(3, opened)
    }

    @Test
    fun stackedBarsSurviveEmptyNegativeAndMismatchedData() {
        rule.setContent {
            KinsightTheme() {
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
            KinsightTheme() {
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
            KinsightTheme() {
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
            KinsightTheme() {
                CategoryLegend(listOf("Games" to Color.Red, "Video" to Color.Blue))
            }
        }
        rule.onNodeWithText("Games").assertExists()
        rule.onNodeWithText("Video").assertExists()
    }

    @Test
    fun aLimitLineIsReadWithTheSummary() {
        rule.setContent {
            KinsightTheme() {
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

    /** The widest duration tick label of any max from 1 min to 24 h, plus the 4 dp gap: the scale gutter at 100 % font. */
    private fun widestGutterDp(): Float {
        var gutter = 0f
        rule.setContent {
            KinsightTheme() {
                val resources = LocalResources.current
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.labelSmall
                val texts = (1L..24 * 60).flatMap { gridTicks(it * 60_000L, ChartScale.DURATION) }.toSet().map { tickText(it, ChartScale.DURATION, resources) }
                gutter = with(density) { texts.maxOf { measurer.measure(it, style).size.width }.toDp().value } + 4f
            }
        }
        rule.waitForIdle()
        return gutter
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths
    fun theScaleGutterStaysNarrow() {
        val gutter = widestGutterDp()
        println("scale gutter en: $gutter dp")
        assertTrue("gutter $gutter dp", gutter in 20f..48f)
    }

    @Test
    @Config(qualifiers = "ru")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theScaleGutterStaysNarrowInRussian() {
        val gutter = widestGutterDp()
        println("scale gutter ru: $gutter dp")
        assertTrue("gutter $gutter dp", gutter in 20f..48f)
    }

    @Test
    fun theDrawnLinesAreExposedForTests() {
        rule.setContent {
            KinsightTheme() {
                Column {
                    StackedBarChart(listOf(listOf(1L), listOf(4L)), colors, listOf("M", "T"), "Stacked", Modifier.testTag("s"), onBarClick = {}, limit = 3L, average = 2L, averageLabel = "Avg")
                    BarChart(listOf(1L, 2L), listOf("M", "T"), "Bars", Modifier.testTag("b"), average = 0L, averageLabel = "Avg")
                }
            }
        }
        rule.onNodeWithTag("s").assert(SemanticsMatcher.expectValue(ChartLimitLine, 3L)).assert(SemanticsMatcher.expectValue(ChartAverageLine, 2L))
        rule.onNodeWithTag("b").assert(SemanticsMatcher.keyNotDefined(ChartLimitLine)).assert(SemanticsMatcher.keyNotDefined(ChartAverageLine))
        rule.onNodeWithText("Avg").assertDoesNotExist() // the note is hidden from TalkBack: the summary has the average
    }
}
