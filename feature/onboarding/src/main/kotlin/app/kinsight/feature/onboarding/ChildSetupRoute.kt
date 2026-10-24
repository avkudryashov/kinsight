package app.kinsight.feature.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kinsight.core.blocking.BlockingState
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ChildSetup
import app.kinsight.core.data.SetupItem
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.location.LocationPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * «Ещё → Настройка не завершена» on a child's phone (UI refresh §6): the steps still missing, one per page with a
 * progress bar and «Позже»; a granted step is never shown again; [onDone] once nothing is left to show. The
 * accessibility step (app blocking spec §5.4) waits for the first snapshot: it is asked only while a parent's rule needs it.
 */
@Composable
fun ChildSetupRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val enforcing by remember { enforcingRules(context) }.collectAsState(initial = null)
    fun read() = ChildSetup.read(context, BlockingState.serviceEnabled(context), enforcing == true)
    var pending by remember { mutableStateOf(read()) }
    LaunchedEffect(enforcing) { pending = read() }
    // SetupItem names: a Bundle keeps a string list across a recreation.
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { pending = read() }
    // A location answer, granted or not, ends that step (as onboarding does); a grant starts tracking now.
    fun answered(item: SetupItem) {
        LocationGraph.refresh(context, rearm = true)
        AppGraph.get(context).launchLocationCheck()
        pending = read()
        skipped = skipped + item.name
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answered(SetupItem.LOCATION) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answered(SetupItem.LOCATION_BACKGROUND) }
    val skippedItems = skipped.mapNotNull { n -> SetupItem.entries.firstOrNull { it.name == n } }.toSet()
    if (enforcing == null) return
    val item = ChildSetup.next(pending, skippedItems)
    LaunchedEffect(item) { if (item == null) onDone() }
    // Pages already shown and left behind (granted or skipped): the step number counts them, not the held-back ones.
    var passed by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(item) { if (item != null && item.name !in passed) passed = passed + item.name }
    if (item == null) return
    val (step, total) = ChildSetup.progress((passed - item.name).size, pending, skippedItems)
    CompositionLocalProvider(LocalStepProgress provides StepProgress(step, total)) {
        ChildSetupStep(
            item,
            onAllow = {
                when (item) {
                    SetupItem.USAGE_ACCESS -> openUsageAccessSettings(context)
                    SetupItem.LOCATION -> location.launch(LocationPermissions.FOREGROUND)
                    SetupItem.LOCATION_BACKGROUND -> background.launch(LocationPermissions.BACKGROUND)
                    SetupItem.BATTERY -> openBatterySettings(context)
                    SetupItem.ACCESSIBILITY -> startFirst(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            onLater = { skipped = skipped + item.name },
        )
    }
}

/** One step of the child's setup (UI refresh §6): why, the button to the system screen, «Позже». */
@Composable
internal fun ChildSetupStep(item: SetupItem, onAllow: () -> Unit, onLater: () -> Unit) {
    val later = stringResource(R.string.onb_later)
    when (item) {
        SetupItem.USAGE_ACCESS -> DisclosureScreen(
            stringResource(R.string.onb_usage_title), stringResource(R.string.onb_usage_body), stringResource(R.string.onb_usage_agree),
            onAllow, onLater, notNowLabel = later,
        )
        SetupItem.LOCATION -> DisclosureScreen(
            stringResource(R.string.onb_location_title), stringResource(R.string.onb_location_body), stringResource(R.string.onb_location_agree),
            onAllow, onLater, notNowLabel = later,
        )
        SetupItem.LOCATION_BACKGROUND -> DisclosureScreen(
            stringResource(R.string.onb_background_title), stringResource(R.string.onb_background_body), stringResource(R.string.onb_open_settings),
            onAllow, onLater, notNowLabel = later,
        )
        SetupItem.BATTERY -> BatteryScreen(BatteryGuide.vendor(Build.MANUFACTURER), unrestricted = false, onOpenSettings = onAllow, onNext = onLater)
        SetupItem.ACCESSIBILITY -> DisclosureScreen(
            stringResource(R.string.onb_accessibility_title), stringResource(R.string.onb_accessibility_body),
            stringResource(R.string.onb_accessibility_allow), onAllow, onLater, notNowLabel = later,
        )
    }
}

/** Whether this phone's own rules need the enforcement service and it would act (ChildSetup.enforcing), per snapshot and consent. */
private fun enforcingRules(context: Context): Flow<Boolean?> =
    AppGraph.get(context).let { g -> combine(g.snapshots.snapshots, g.settingsRepository.usageSettings) { s, u -> ChildSetup.enforcing(s, u) } }
        .distinctUntilChanged()
