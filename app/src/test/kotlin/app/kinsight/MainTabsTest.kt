package app.kinsight

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.theme.KinsightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI refresh §2: the parent's «Обзор · Карта · Ещё», the child's «Обзор · Ещё». */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestKinsightApplication::class)
class MainTabsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aParentHasTheMapAndAChildDoesNot() {
        assertEquals(listOf(MainTab.OVERVIEW, MainTab.MAP, MainTab.MORE), mainTabs(Role.PARENT))
        assertEquals(listOf(MainTab.OVERVIEW, MainTab.MORE), mainTabs(Role.CHILD))
        assertEquals(listOf(MainTab.OVERVIEW, MainTab.MORE), mainTabs(null))
    }

    @Test
    fun theBarShowsOverviewMapMoreForAParent() {
        rule.setContent { KinsightTheme { MainNavigation(mainTabs(Role.PARENT), MainTab.OVERVIEW, onSelect = {}) {} } }
        rule.onNodeWithTag("nav_overview").assertExists()
        rule.onNodeWithTag("nav_map").assertExists()
        rule.onNodeWithTag("nav_more").assertExists()
        rule.onNodeWithText("Map").assertExists()
        rule.onNodeWithText("More").assertExists()
    }

    @Test
    fun theBarShowsOverviewMoreForAChild() {
        rule.setContent { KinsightTheme { MainNavigation(mainTabs(Role.CHILD), MainTab.OVERVIEW, onSelect = {}) {} } }
        rule.onNodeWithTag("nav_overview").assertExists()
        rule.onNodeWithTag("nav_more").assertExists()
        rule.onNodeWithTag("nav_map").assertDoesNotExist()
    }

    @Test
    fun aScreenOpenedFromMoreKeepsMoreSelected() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = OverviewDest) {
                composable<OverviewDest> {}
                composable<MoreDest> {}
                composable<GroupDest> {}
            }
        }
        rule.runOnIdle {
            nav.navigate(MoreDest)
            nav.navigate(GroupDest)
        }
        rule.runOnIdle { assertEquals(MainTab.MORE, selectedTab(nav.currentBackStack.value)) }
        rule.runOnIdle { nav.popBackStack(OverviewDest, inclusive = false) }
        rule.runOnIdle { assertEquals(MainTab.OVERVIEW, selectedTab(nav.currentBackStack.value)) }
    }
}
