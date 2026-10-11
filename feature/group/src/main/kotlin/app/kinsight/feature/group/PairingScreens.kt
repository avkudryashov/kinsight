package app.kinsight.feature.group

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.InviteState
import app.kinsight.core.data.JoinState
import app.kinsight.core.data.PairFailure
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.QrCode
import app.kinsight.core.sync.pairing.Invite
import app.kinsight.core.sync.pairing.PairingSas
import app.kinsight.core.transport.ble.RadioProblem

fun PairFailure.messageRes(): Int = when (this) {
    PairFailure.NO_PERMISSION -> R.string.pair_fail_no_permission
    PairFailure.BLUETOOTH_OFF -> R.string.pair_fail_bluetooth_off
    PairFailure.RADIO -> R.string.pair_fail_radio
    PairFailure.NOT_FOUND -> R.string.pair_fail_not_found
    PairFailure.LINK -> R.string.pair_fail_link
    PairFailure.WRONG_ADMIN -> R.string.pair_fail_wrong_admin
    PairFailure.BAD_TOKEN -> R.string.pair_fail_bad_token
    PairFailure.EXPIRED -> R.string.pair_fail_expired
    PairFailure.DECLINED -> R.string.pair_fail_declined
    PairFailure.REMOVED_BEFORE -> R.string.pair_fail_removed_before
    PairFailure.INVALID -> R.string.pair_fail_invalid
    PairFailure.NOT_ADMIN -> R.string.pair_fail_not_admin
    PairFailure.ALREADY_IN_GROUP -> R.string.pair_fail_already_in_group
    PairFailure.IDENTITY -> R.string.pair_fail_identity
}

@Composable
private fun roleLabel(role: Role): String = stringResource(if (role == Role.CHILD) R.string.grp_role_child else R.string.grp_role_parent)

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

/** Spec §9.5: the QR fills the width, a countdown to its expiry; the admin confirms the joiner in a dialog. */
@Composable
fun InviteScreen(
    state: InviteState,
    role: Role,
    nowMs: Long,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    confirming: Boolean = false,
) = Centered {
    Text(
        stringResource(R.string.inv_title, roleLabel(role)),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    when (state) {
        InviteState.Starting -> CircularProgressIndicator()
        is InviteState.Showing -> {
            InviteQr(state.uri, state.expiresAtMs, nowMs)
            state.problem?.let { RadioProblemText(it) }
        }
        is InviteState.Confirm -> {
            InviteQr(state.uri, state.expiresAtMs, nowMs)
            AlertDialog(
                onDismissRequest = {}, // an explicit answer only: a stray tap outside neither adds nor refuses
                modifier = Modifier.dialogTestTags(), // its own window: the root flag does not reach it
                title = { Text(stringResource(R.string.inv_confirm_title, state.name, roleLabel(state.role))) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.code.isNotEmpty()) {
                            val spoken = stringResource(R.string.inv_confirm_code, state.code.toList().joinToString(", "))
                            Text(
                                stringResource(R.string.inv_confirm_code, PairingSas.display(state.code)),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("inv_code").semantics { contentDescription = spoken },
                            )
                        }
                        if (confirming) {
                            Row(Modifier.testTag("inv_adding"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Text(stringResource(R.string.inv_adding))
                            }
                        } else {
                            Text(stringResource(if (state.code.isEmpty()) R.string.inv_confirm_body_nocode else R.string.inv_confirm_body))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onConfirm, Modifier.testTag("inv_confirm"), enabled = !confirming) { Text(stringResource(R.string.inv_confirm)) }
                },
                dismissButton = {
                    TextButton(onDecline, Modifier.testTag("inv_decline"), enabled = !confirming) { Text(stringResource(R.string.inv_decline)) }
                },
            )
        }
        is InviteState.Added -> {
            Text(stringResource(R.string.inv_added, state.name), textAlign = TextAlign.Center)
            Button(onClose, Modifier.testTag("inv_done")) { Text(stringResource(R.string.inv_done)) }
        }
        InviteState.Expired -> {
            Text(stringResource(R.string.inv_expired), textAlign = TextAlign.Center)
            Button(onRetry, Modifier.testTag("inv_retry")) { Text(stringResource(R.string.inv_new_code)) }
        }
        is InviteState.Failed -> {
            Text(stringResource(state.reason.messageRes()), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Button(onRetry, Modifier.testTag("inv_retry")) { Text(stringResource(R.string.pair_retry)) }
        }
    }
    if (state !is InviteState.Added) TextButton(onClose, Modifier.testTag("inv_close")) { Text(stringResource(R.string.inv_cancel)) }
}

/** Whole seconds until [expiresAtMs], never negative. */
internal fun secondsLeft(expiresAtMs: Long, nowMs: Long): Long = ((expiresAtMs - nowMs) / 1000).coerceAtLeast(0)

/**
 * What a screen reader is told about the countdown: «within N minutes» (N rounded up, so it changes exactly at each
 * minute boundary), then null for the last 30 seconds («less than 30 seconds»). The visible m:ss is not announced.
 */
internal fun spokenMinutes(secondsLeft: Long): Int? = if (secondsLeft <= 30) null else ((secondsLeft + 59) / 60).toInt()

@Composable
private fun InviteQr(uri: String, expiresAtMs: Long, nowMs: Long) {
    QrCode(uri, stringResource(R.string.inv_qr_description), Modifier.fillMaxWidth().widthIn(max = 420.dp))
    Text(stringResource(R.string.inv_scan_hint), textAlign = TextAlign.Center)
    val left = secondsLeft(expiresAtMs, nowMs)
    val minutes = spokenMinutes(left)
    val spoken = if (minutes == null) {
        stringResource(R.string.inv_expires_soon_spoken)
    } else {
        pluralStringResource(R.plurals.inv_expires_spoken, minutes, minutes)
    }
    Text(
        stringResource(R.string.inv_expires_in, left / 60, left % 60),
        style = MaterialTheme.typography.titleMedium,
        // Only the coarse text is in the semantics, so the polite live region speaks when it changes, not every second.
        modifier = Modifier.testTag("inv_countdown").clearAndSetSemantics {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
    )
}

/** The host's standing radio problem: a human line, then the technical detail for support. The QR stays usable. */
@Composable
private fun RadioProblemText(problem: RadioProblem) {
    Text(
        stringResource(if (problem.retrying) R.string.inv_problem_retrying else R.string.inv_problem),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Text(problem.detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

/** The joining phone (spec §9.4): progress with Cancel until the welcome is being stored, then Retry on failure. */
@Composable
fun JoinScreen(state: JoinState, adminName: String, onRetry: () -> Unit, onBack: () -> Unit) = Centered {
    Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    when (state) {
        JoinState.Searching -> Progress(stringResource(R.string.join_searching, adminName))
        JoinState.Connecting -> Progress(stringResource(R.string.join_connecting))
        is JoinState.Waiting -> {
            Progress(stringResource(if (state.code.isEmpty()) R.string.join_waiting_nocode else R.string.join_waiting, state.adminName))
            if (state.code.isNotEmpty()) {
                val spoken = stringResource(R.string.join_code, state.code.toList().joinToString(", "))
                Text(
                    stringResource(R.string.join_code, PairingSas.display(state.code)),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("join_code").semantics { contentDescription = spoken },
                )
            }
        }
        JoinState.Saving -> Progress(stringResource(R.string.join_saving))
        JoinState.Joined -> Text(stringResource(R.string.join_joined), textAlign = TextAlign.Center)
        is JoinState.Failed -> {
            Text(stringResource(state.reason.messageRes()), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Button(onRetry, Modifier.testTag("join_retry")) { Text(stringResource(R.string.pair_retry)) }
            TextButton(onBack) { Text(stringResource(R.string.join_back)) }
        }
    }
    when (state) {
        JoinState.Searching, JoinState.Connecting, is JoinState.Waiting ->
            TextButton(onBack, Modifier.testTag("join_cancel")) { Text(stringResource(R.string.join_cancel)) }
        else -> Unit
    }
}

@Composable
private fun Progress(text: String) {
    CircularProgressIndicator()
    Text(text, textAlign = TextAlign.Center)
}

/** Maximum brightness and screen on while composed (spec §9.5); both restored when it leaves the composition. */
@Composable
internal fun KeepScreenBright() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val before = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = before }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * The invite screen. The invite lives only while the screen is visible: stopping (leaving, the app going to the
 * background) closes it and its radio; coming back opens a fresh code. A configuration change keeps it.
 */
@Composable
fun InviteRoute(role: Role, onClose: () -> Unit) {
    val context = LocalContext.current
    val vm: InviteViewModel = viewModel(key = "invite-$role") {
        val g = AppGraph.get(context)
        InviteViewModel(g.pairing, g.prefs, role)
    }
    InviteRoute(vm, role, onClose)
}

@Composable
internal fun InviteRoute(vm: InviteViewModel, role: Role, onClose: () -> Unit) {
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    val confirming by vm.confirming.collectAsStateWithLifecycle()
    val now by rememberNowMs(periodMs = 1_000L)
    LifecycleStartEffect(vm) {
        vm.resume()
        onStopOrDispose { if (activity?.isChangingConfigurations != true) vm.close() }
    }
    if (state is InviteState.Showing || state is InviteState.Confirm) KeepScreenBright()
    InviteScreen(
        state,
        role,
        now,
        onConfirm = vm::confirm,
        onDecline = vm::decline,
        onRetry = vm::start,
        onClose = {
            vm.close()
            onClose()
        },
        confirming = confirming,
    )
}

/**
 * The join screen for [invite]. [consent] is the child's consent from onboarding (no consent: joins without sharing).
 * Leaving the screen cancels the attempt, except while the welcome is being stored; back is held off then too.
 */
@Composable
fun JoinRoute(invite: Invite, name: String, consent: Boolean, onJoined: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val vm: JoinViewModel = viewModel(key = "join-${invite.toUri()}-$consent") {
        JoinViewModel(AppGraph.get(context).pairing, invite, name, consent)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) {
        vm.resume()
        onDispose { if (activity?.isChangingConfigurations != true) vm.cancel() }
    }
    LaunchedEffect(state) { if (state == JoinState.Joined) onJoined() }
    BackHandler(enabled = state == JoinState.Saving) {}
    JoinScreen(state, invite.adminName, vm::start) {
        vm.cancel()
        onBack()
    }
}
