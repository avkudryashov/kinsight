package app.kinsight

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.DetailRange
import app.kinsight.core.data.Period
import app.kinsight.core.data.ResetKind
import app.kinsight.core.data.ResetStatus
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.component.CenteredContent
import app.kinsight.core.designsystem.component.ErrorState
import app.kinsight.core.model.DeviceId
import app.kinsight.feature.dashboard.DashboardRoute
import app.kinsight.feature.devicedetail.AllAppsRoute
import app.kinsight.feature.devicedetail.AppDetailRoute
import app.kinsight.feature.devicedetail.DeviceDetailRoute
import app.kinsight.feature.devicedetail.ThresholdsRoute
import app.kinsight.feature.group.GroupRoute
import app.kinsight.feature.group.InviteRoute
import app.kinsight.feature.group.JoinRoute
import app.kinsight.feature.onboarding.BatteryGuideRoute
import app.kinsight.feature.onboarding.ConsentRoute
import app.kinsight.feature.onboarding.OnboardingRoute
import app.kinsight.feature.settings.KeyErrorRoute
import app.kinsight.feature.settings.RemovedRoute
import app.kinsight.feature.settings.SettingsRoute
import app.kinsight.feature.settings.TransparencyRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import kotlin.reflect.KClass

/**
 * The root (spec §9.1): the screen follows [AppStart] over the group state, the prefs and the app-wide wipe status.
 * Each root is its own back stack entry, so a root that comes back (a second first run after leaving) gets fresh view
 * models, while a process restart restores the root it was on. Test tags are exposed as resource ids for adb.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KinsightApp(
    invite: StateFlow<String?>,
    onInviteConsumed: () -> Unit,
    appVersion: String,
    openDevice: StateFlow<String?> = MutableStateFlow(null),
    onDeviceOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val group by graph.group.state.collectAsStateWithLifecycle(initialValue = GroupState.Loading)
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle(initialValue = null)
    val reset by graph.reset.status.collectAsStateWithLifecycle()
    val start = AppStart.of(group, prefs, reset)
    val roots = rememberNavController()
    LaunchedEffect(start) { roots.showRoot(start) }
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavHost(roots, startDestination = RootDest(Start.LOADING.name)) {
            composable<RootDest> { entry ->
                when (Start.valueOf(entry.toRoute<RootDest>().start)) {
                    Start.LOADING -> Box(Modifier.fillMaxSize())
                    Start.KEY_ERROR -> KeyErrorRoute(
                        (group as? GroupState.KeyError)?.message ?: (group as? GroupState.StorageError)?.message.orEmpty(),
                    )
                    Start.REMOVED -> RemovedRoute()
                    Start.LEAVING -> Progress(stringResource(R.string.app_leaving), "app_leaving")
                    Start.RESETTING -> Progress(stringResource(R.string.app_resetting), "app_resetting")
                    Start.RESET_FAILED -> ResetFailed(
                        reason = (reset as? ResetStatus.Failed)?.reason.orEmpty(),
                        onRetry = {
                            when ((reset as? ResetStatus.Failed)?.kind) {
                                ResetKind.NEW_IDENTITY -> graph.reset.launchResetIdentity()
                                ResetKind.FORGET_GROUP -> graph.reset.launchForgetGroup()
                                null -> Unit
                            }
                        },
                        onDismiss = graph.reset::clearFailure.takeIf { graph.reset.dismissible },
                    )
                    Start.ONBOARDING -> {
                        val pending by invite.collectAsStateWithLifecycle()
                        OnboardingRoute(
                            initialInvite = pending,
                            onInviteConsumed = onInviteConsumed,
                            onFinished = {}, // prefs.onboardingDone flips and the root switches to MAIN by itself
                            pairContent = { inv, name, consent, onJoined, onBack -> JoinRoute(inv, name, consent, onJoined, onBack) },
                        )
                    }
                    Start.MAIN -> {
                        val pending by invite.collectAsStateWithLifecycle()
                        LaunchedEffect(pending) {
                            if (pending != null) {
                                Toast.makeText(context, R.string.app_already_in_group, Toast.LENGTH_LONG).show()
                                onInviteConsumed()
                            }
                        }
                        MainScaffold(appVersion, openDevice, onDeviceOpened)
                    }
                }
            }
        }
    }
}

/** Replaces the single root entry (its view models are cleared with it) when [AppStart.nextRoot] says so. */
private fun NavHostController.showRoot(computed: Start) {
    val current = currentBackStackEntry?.takeIf { it.destination.hasRoute(RootDest::class) }?.toRoute<RootDest>()?.start
    val next = AppStart.nextRoot(current?.let(Start::valueOf), computed) ?: return
    navigate(RootDest(next.name)) {
        popUpTo<RootDest> { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun Progress(text: String, tag: String) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** LocalReset failed part way: the phone is in an unknown state, so this blocks until a retry works or the user dismisses. */
@Composable
private fun ResetFailed(reason: String, onRetry: () -> Unit, onDismiss: (() -> Unit)?) = ErrorState(
    title = stringResource(R.string.app_reset_failed_title),
    body = stringResource(R.string.app_reset_failed_body, reason),
    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onDismiss != null) {
            TextButton(onDismiss, Modifier.testTag("app_reset_dismiss")) { Text(stringResource(R.string.app_reset_dismiss)) }
        }
        Button(onRetry, Modifier.testTag("app_reset_retry")) { Text(stringResource(R.string.app_reset_retry)) }
    }
}

private class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector, val tag: String)

private val tabs = listOf(
    Tab(OverviewDest, OverviewDest::class, R.string.nav_overview, Icons.Filled.Home, "nav_overview"),
    Tab(GroupDest, GroupDest::class, R.string.nav_group, Icons.Filled.Person, "nav_group"),
    Tab(SettingsDest, SettingsDest::class, R.string.nav_settings, Icons.Filled.Settings, "nav_settings"),
)

@Composable
private fun MainScaffold(appVersion: String, openDevice: StateFlow<String?>, onDeviceOpened: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    // Screens without their own top bar keep clear of the status bar and cutout; the suite handles the navigation bar.
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    // A leave that failed or was refused comes back here as a fresh root: its error dialog lives on the group tab.
    val leaveError by graph.group.leaveError.collectAsStateWithLifecycle()
    LaunchedEffect(leaveError) {
        if (leaveError != null && nav.currentDestination?.hasRoute(GroupDest::class) != true) nav.selectTab(GroupDest)
    }
    // An integrity alert was tapped: the overview tab, then that child's detail on top of it.
    val device by openDevice.collectAsStateWithLifecycle()
    LaunchedEffect(device) {
        val hex = device ?: return@LaunchedEffect
        nav.selectTab(OverviewDest)
        nav.navigate(DeviceDest(hex)) { launchSingleTop = true }
        onDeviceOpened()
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEach { t ->
                val selected = entry?.destination?.hierarchy?.any { it.hasRoute(t.type) } == true
                item(
                    selected = selected,
                    // Reselecting the current tab returns to its root (Material navigation bar guidance).
                    onClick = { if (selected) nav.popBackStack(t.route, inclusive = false) else nav.selectTab(t.route) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(stringResource(t.label)) },
                    modifier = Modifier.testTag(t.tag),
                )
            }
        },
    ) {
        CenteredContent {
            NavHost(nav, startDestination = OverviewDest) {
                composable<OverviewDest> {
                    Box(Modifier.windowInsetsPadding(topInsets)) {
                        DashboardRoute(
                            onOpenDevice = { nav.navigate(DeviceDest(it.hex)) },
                            onConsent = { nav.navigate(ConsentDest) },
                            // The group tab's child invite, so closing it lands on the group, as the invite there does.
                            onInviteChild = {
                                nav.selectTab(GroupDest)
                                nav.navigate(InviteDest(Role.CHILD.name))
                            },
                            // A key error: Settings holds Diagnostics and the identity reset.
                            onOpenSettings = { nav.selectTab(SettingsDest) },
                        )
                    }
                }
                composable<GroupDest> {
                    Box(Modifier.windowInsetsPadding(topInsets)) { GroupRoute(onInvite = { nav.navigate(InviteDest(it.name)) }) }
                }
                composable<SettingsDest> {
                    SettingsRoute(
                        appVersion = appVersion,
                        onConsent = { nav.navigate(ConsentDest) },
                        onTransparency = { nav.navigate(TransparencyDest) },
                        onBattery = { nav.navigate(BatteryDest) },
                        onDiagnostics = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) },
                        onNewPhone = { nav.navigate(InviteDest(Role.PARENT.name, transfer = true)) }, // spec §7.3
                    )
                }
                composable<DeviceDest> { e ->
                    val r = e.toRoute<DeviceDest>()
                    DeviceDetailRoute(
                        DeviceId(r.id),
                        onAllApps = { range -> nav.navigate(AllAppsDest(r.id, range.period.name, range.start.toString(), range.end.toString())) },
                        onBack = { nav.popBackStack() },
                        onOpenApp = { pkg, range -> nav.navigate(appDest(r.id, pkg, range)) }, // the app opens on the same period
                        onThresholds = { nav.navigate(ThresholdsDest(r.id)) },
                    )
                }
                composable<ThresholdsDest> { e -> ThresholdsRoute(DeviceId(e.toRoute<ThresholdsDest>().id), onBack = { nav.popBackStack() }) }
                composable<AllAppsDest> { e ->
                    val r = e.toRoute<AllAppsDest>()
                    val range = DetailRange(Period.valueOf(r.period), LocalDate.parse(r.start), LocalDate.parse(r.end))
                    AllAppsRoute(DeviceId(r.id), range, onBack = { nav.popBackStack() }, onOpenApp = { pkg -> nav.navigate(appDest(r.id, pkg, range)) })
                }
                composable<AppDest> { e ->
                    val r = e.toRoute<AppDest>()
                    val range = DetailRange(Period.valueOf(r.period), LocalDate.parse(r.start), LocalDate.parse(r.end))
                    AppDetailRoute(DeviceId(r.id), r.packageName, range, onBack = { nav.popBackStack() })
                }
                composable<InviteDest> { e ->
                    val d = e.toRoute<InviteDest>()
                    InviteRoute(Role.valueOf(d.role), transfer = d.transfer, onClose = { nav.popBackStack() })
                }
                composable<TransparencyDest> { TransparencyRoute(onBack = { nav.popBackStack() }) }
                composable<ConsentDest> { ConsentRoute(onDone = { nav.popBackStack() }) }
                composable<BatteryDest> { BatteryGuideRoute(onDone = { nav.popBackStack() }) }
            }
        }
    }
}

private fun NavHostController.selectTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun appDest(id: String, packageName: String, range: DetailRange) =
    AppDest(id, packageName, range.period.name, range.start.toString(), range.end.toString())
