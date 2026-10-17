package app.kinsight.feature.onboarding

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.ChildSetup
import app.kinsight.core.data.SetupItem
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.location.LocationPermissions

/**
 * «Ещё → Настройка не завершена» on a child's phone (UI refresh §6): the steps still missing, one per page with a
 * progress bar and «Позже»; a granted step is never shown again; [onDone] once nothing is left to show.
 */
@Composable
fun ChildSetupRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(ChildSetup.read(context)) }
    val total = remember { pending.size.coerceAtLeast(1) }
    // SetupItem names: a Bundle keeps a string list across a recreation.
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { pending = ChildSetup.read(context) }
    // A location answer, granted or not, ends that step (as onboarding does); a grant starts tracking now.
    fun answered(item: SetupItem) {
        LocationGraph.refresh(context, rearm = true)
        AppGraph.get(context).launchLocationCheck()
        pending = ChildSetup.read(context)
        skipped = skipped + item.name
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answered(SetupItem.LOCATION) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answered(SetupItem.LOCATION_BACKGROUND) }
    val item = ChildSetup.next(pending, skipped.mapNotNull { n -> SetupItem.entries.firstOrNull { it.name == n } }.toSet())
    LaunchedEffect(item) { if (item == null) onDone() }
    if (item == null) return
    val left = pending.count { it.name !in skipped }
    CompositionLocalProvider(LocalStepProgress provides StepProgress((total - left + 1).coerceIn(1, total), total)) {
        ChildSetupStep(
            item,
            onAllow = {
                when (item) {
                    SetupItem.USAGE_ACCESS -> openUsageAccessSettings(context)
                    SetupItem.LOCATION -> location.launch(LocationPermissions.FOREGROUND)
                    SetupItem.LOCATION_BACKGROUND -> background.launch(LocationPermissions.BACKGROUND)
                    SetupItem.BATTERY -> openBatterySettings(context)
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
    }
}
