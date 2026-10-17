package app.kinsight

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import app.kinsight.core.data.Role
import kotlin.reflect.KClass

/** The bottom bar's tabs (UI refresh §2). */
enum class MainTab { OVERVIEW, MAP, MORE }

/** A parent's «Обзор · Карта · Ещё»; a child's, and while the role is unknown, «Обзор · Ещё» (the map is a parent's). */
internal fun mainTabs(role: Role?): List<MainTab> =
    if (role == Role.PARENT) listOf(MainTab.OVERVIEW, MainTab.MAP, MainTab.MORE) else listOf(MainTab.OVERVIEW, MainTab.MORE)

internal fun MainTab.route(): Any = when (this) {
    MainTab.OVERVIEW -> OverviewDest
    MainTab.MAP -> MapDest
    MainTab.MORE -> MoreDest
}

private fun MainTab.type(): KClass<*> = when (this) {
    MainTab.OVERVIEW -> OverviewDest::class
    MainTab.MAP -> MapDest::class
    MainTab.MORE -> MoreDest::class
}

private fun MainTab.label(): Int = when (this) {
    MainTab.OVERVIEW -> R.string.nav_overview
    MainTab.MAP -> R.string.nav_map
    MainTab.MORE -> R.string.nav_more
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.OVERVIEW -> Icons.Filled.Home
    MainTab.MAP -> Icons.Filled.LocationOn
    MainTab.MORE -> Icons.Filled.Menu
}

private fun MainTab.tag(): String = "nav_" + name.lowercase()

/** The tab of the deepest tab root in the back stack: a screen opened from «Ещё» keeps «Ещё» selected. */
internal fun selectedTab(stack: List<NavBackStackEntry>): MainTab? = stack.asReversed().firstNotNullOfOrNull { e ->
    MainTab.entries.firstOrNull { e.destination.hasRoute(it.type()) }
}

/** The navigation suite (a bar on a phone, a rail on a wide screen) with [tabs]. */
@Composable
internal fun MainNavigation(tabs: List<MainTab>, selected: MainTab?, onSelect: (MainTab) -> Unit, content: @Composable () -> Unit) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEach { t ->
                item(
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    icon = { Icon(t.icon(), contentDescription = null) },
                    label = { Text(stringResource(t.label())) },
                    modifier = Modifier.testTag(t.tag()),
                )
            }
        },
        content = content,
    )
}
