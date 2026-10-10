package app.kinsight.feature.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Role
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.transport.ble.BlePermissions
import app.kinsight.core.usage.AppOpsUsageAccess
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

internal fun readEnv(context: Context) = Env(
    nearbyGranted = BlePermissions.missing(context).isEmpty(),
    notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    usageAccessGranted = AppOpsUsageAccess(context).granted(),
)

internal fun unrestrictedBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

internal fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

internal fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()))
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
}

/**
 * Who the consent screen names: the other parents of the group, for a child's phone only. Null for an adult or
 * without a ready group: then the consent screen is never shown (Play Stalkerware ruling, adults never share).
 */
internal fun consentRecipients(state: GroupState): String? {
    val ready = state as? GroupState.Ready ?: return null
    if (ready.self.role != Role.CHILD) return null
    return ready.members.filter { it.role == Role.PARENT && !it.isSelf }.joinToString(", ") { it.name }
}

/**
 * The first run (spec §9.4). [pairContent] is the join screen of feature/group (wired by app): it pairs with the
 * invite and calls onJoined. Nothing is read from UsageStatsManager here; collection starts only after consent and
 * joining (core/usage gate).
 */
@Composable
fun OnboardingRoute(
    initialInvite: String?,
    onInviteConsumed: () -> Unit,
    onFinished: () -> Unit,
    pairContent: @Composable (invite: Invite, name: String, consent: Boolean, onJoined: () -> Unit, onBack: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val vm: OnboardingViewModel = viewModel {
        val g = AppGraph.get(context)
        OnboardingViewModel(g.prefs, g.group, Build.MODEL)
    }
    // A deep link (system camera or adb) arrives at any time: apply it once, then the app forgets it.
    LaunchedEffect(initialInvite) {
        if (initialInvite != null) {
            vm.onInviteText(initialInvite)
            onInviteConsumed()
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var unrestricted by remember { mutableStateOf(unrestrictedBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.env = readEnv(context)
        unrestricted = unrestrictedBattery(context)
        if (state.step == Step.USAGE_ACCESS && vm.env.usageAccessGranted) vm.next()
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.env = readEnv(context)
        if (vm.env.nearbyGranted || state.path == Path.CREATE) vm.next() else vm.show(OnboardingMessage.NEARBY_REQUIRED)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.env = readEnv(context)
        vm.next()
    }
    BackHandler(enabled = state.step != Step.WELCOME && state.step != Step.DONE) { vm.back() }
    val child = state.invite?.role == DeviceRole.CHILD
    val inviteError = when (message) {
        OnboardingMessage.INVALID_INVITE -> stringResource(R.string.onb_scan_invalid)
        OnboardingMessage.UNSUPPORTED_INVITE -> stringResource(R.string.onb_scan_unsupported)
        else -> null
    }

    when (state.step) {
        Step.WELCOME -> WelcomeScreen(onCreate = { vm.choose(Path.CREATE) }, onJoin = { vm.choose(Path.JOIN) }, message = inviteError)
        Step.NAME -> NameScreen(state.name, message == OnboardingMessage.NAME_INVALID, vm::setName, vm::next, vm::back)
        Step.PARENT_INFO -> ParentInfoScreen(vm::next)
        Step.NEARBY -> DisclosureScreen(
            title = stringResource(R.string.onb_nearby_title),
            body = stringResource(R.string.onb_nearby_body),
            agreeLabel = stringResource(R.string.onb_agree),
            onAgree = { nearby.launch(BlePermissions.REQUIRED.toTypedArray()) },
            onNotNow = { if (state.path == Path.CREATE) vm.next() else vm.show(OnboardingMessage.NEARBY_REQUIRED) },
            note = if (message == OnboardingMessage.NEARBY_REQUIRED) stringResource(R.string.onb_nearby_required) else null,
        )
        Step.SCAN -> ScanScreen(
            message = inviteError ?: if (message == OnboardingMessage.SCANNER_UNAVAILABLE) stringResource(R.string.onb_scan_unavailable) else null,
            onScan = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { code -> code.rawValue?.let(vm::onInviteText) ?: vm.show(OnboardingMessage.INVALID_INVITE) }
                    .addOnFailureListener { vm.show(OnboardingMessage.SCANNER_UNAVAILABLE) }
            },
            onBack = vm::back,
        )
        // OnboardingFlow puts CONSENT only on a child invite's path.
        Step.CONSENT -> ConsentScreen(state.invite?.adminName.orEmpty(), onAgree = { vm.consent(true) }, onNotNow = { vm.consent(false) })
        Step.PAIR -> pairContent(state.invite!!, state.name.trim(), child && state.consent, vm::next, vm::back)
        Step.USAGE_ACCESS -> DisclosureScreen(
            title = stringResource(R.string.onb_usage_title),
            body = stringResource(R.string.onb_usage_body),
            agreeLabel = stringResource(R.string.onb_usage_agree),
            onAgree = { openUsageAccessSettings(context) },
            onNotNow = vm::next,
        )
        Step.NOTIFICATIONS -> DisclosureScreen(
            title = stringResource(R.string.onb_notifications_title),
            body = stringResource(if (child) R.string.onb_notifications_body_child else R.string.onb_notifications_body_parent),
            agreeLabel = stringResource(R.string.onb_agree),
            onAgree = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.next()
            },
            onNotNow = vm::next,
        )
        Step.BATTERY -> BatteryScreen(BatteryGuide.vendor(Build.MANUFACTURER), unrestricted, { openBatterySettings(context) }, vm::next)
        Step.DONE -> DoneScreen(
            child = child,
            message = if (message == OnboardingMessage.CREATE_FAILED) stringResource(R.string.onb_create_failed) else null,
            onDone = { vm.finish(onFinished) },
        )
    }
}

/**
 * Settings → «Делиться» turned on without a recorded consent: the same consent screen, recipients = the parents of
 * the group; «Согласен» records consent and turns sharing on. A child's phone only: on an adult's phone, or without
 * a group, it closes at once and records nothing.
 */
@Composable
fun ConsentRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val group by graph.group.state.collectAsState(initial = GroupState.Loading)
    val scope = rememberCoroutineScope()
    val current = group
    if (current is GroupState.Loading) return
    val recipients = consentRecipients(current)
    if (recipients == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    ConsentScreen(
        recipients = recipients,
        onAgree = {
            scope.launch {
                runCatching { graph.settingsRepository.consentAndShare() }
                onDone()
            }
        },
        onNotNow = onDone,
    )
}

@Composable
fun BatteryGuideRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(unrestrictedBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = unrestrictedBattery(context) }
    BatteryScreen(BatteryGuide.vendor(Build.MANUFACTURER), unrestricted, { openBatterySettings(context) }, onDone)
}
