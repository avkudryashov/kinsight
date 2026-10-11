package app.kinsight.feature.devicedetail

import androidx.compose.runtime.Composable
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppDetail
import app.kinsight.core.data.AppList
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.CategoryTotal
import app.kinsight.core.data.DayTotal
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.DeviceDetail
import app.kinsight.core.data.HourTotal
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Period
import app.kinsight.core.data.Role
import app.kinsight.core.data.Sharing
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.preview.PreviewTheme
import app.kinsight.core.designsystem.preview.ThemePreviews
import app.kinsight.core.model.DeviceId
import java.time.LocalDate

private const val NOW = 1_760_000_000_000L
private val TODAY = LocalDate.of(2026, 10, 10)
private val KID = MemberView(DeviceId("cc".repeat(16)), "Masha", Role.CHILD, isAdmin = false, isSelf = false, lastSeenMs = NOW)
private val WEEK = DetailRange(Period.WEEK, TODAY.minusDays(6), TODAY)
private val APPS = listOf(
    AppUsage("com.example.video", "Video", null, 240 * 60_000L),
    AppUsage("com.example.game", "Game", null, 150 * 60_000L),
    AppUsage("com.example.chat", "Chat", null, 90 * 60_000L),
)
private val DAYS = WEEK.dates().mapIndexed { i, d -> DayTotal(d, (40L + 20 * i) * 60_000L, 10 + i) }
private val CATEGORIES = listOf(
    CategoryTotal(AppCategory.VIDEO, 240 * 60_000L),
    CategoryTotal(AppCategory.GAMES, 150 * 60_000L),
    CategoryTotal(AppCategory.SOCIAL, 90 * 60_000L),
)
private val WEEK_DETAIL = DeviceDetail(
    KID, WEEK, DAYS, DAYS.sumOf { it.totalMs }, DAYS.sumOf { it.unlocks }, APPS, emptyList(), Sharing.On,
    canGoBack = true, canGoForward = false, categories = CATEGORIES,
    dayCategories = DAYS.map { mapOf(AppCategory.VIDEO to it.totalMs / 2, AppCategory.GAMES to it.totalMs / 2) },
    today = TODAY, updatedMs = NOW - 15 * 60_000,
)
private val TOTAL_LIMIT = ThresholdRule.of(KID.id, ThresholdTarget.Total, 3 * 3_600_000L)!!

@ThemePreviews
@Composable
internal fun DeviceDetailWeekPreview() = PreviewTheme {
    DeviceDetailScreen(WEEK_DETAIL, onPeriod = {}, onAllApps = {}, onBack = {}, thresholds = listOf(TOTAL_LIMIT), nowMs = NOW)
}

@ThemePreviews
@Composable
internal fun DeviceDetailDayPreview() = PreviewTheme {
    val hours = (0..23).map { h ->
        val ms = if (h in 15..21) 25 * 60_000L else 0L
        HourTotal(h, ms, if (ms > 0) 3 else 0, if (ms > 0) mapOf(AppCategory.VIDEO to ms) else emptyMap())
    }
    DeviceDetailScreen(
        WEEK_DETAIL.copy(range = DetailRange(Period.DAY, TODAY, TODAY), days = listOf(DAYS.last()), totalMs = DAYS.last().totalMs, hours = hours),
        onPeriod = {}, onAllApps = {}, onBack = {}, nowMs = NOW,
    )
}

@ThemePreviews
@Composable
internal fun AppDetailPreview() = PreviewTheme {
    val days = WEEK.dates().map { DayTotal(it, 30 * 60_000L, 0, known = true) }
    AppDetailScreen(
        AppDetail(
            KID, "com.example.game", WEEK, "Game", null, AppCategory.GAMES, overridden = false, days, days.sumOf { it.totalMs }, null, rank = 2,
            canChangeCategory = true, canGoBack = true, canGoForward = false, today = TODAY, defaultCategory = AppCategory.GAMES,
        ),
        onPeriod = {}, onBack = {},
    )
}

@ThemePreviews
@Composable
internal fun AllAppsPreview() = PreviewTheme { AllAppsScreen(AppList(KID, WEEK, APPS, TODAY), onBack = {}) }

@ThemePreviews
@Composable
internal fun ThresholdsPreview() = PreviewTheme {
    ThresholdsScreen(
        ThresholdsState("Masha", canEdit = true, rules = listOf(TOTAL_LIMIT), apps = APPS),
        pending = emptySet(), onSave = { _, _ -> }, onDelete = {}, onUndo = {}, onCommitDelete = {}, onBack = {},
    )
}

@ThemePreviews
@Composable
internal fun MissingDevicePreview() = PreviewTheme { MissingDevice(onBack = {}) }
