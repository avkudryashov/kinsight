package app.kinsight

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Y3 parked minor: «Перенести на новый телефон» tapped twice opens one transfer invite (spec §7.3), not two. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestKinsightApplication::class)
class TransferInviteNavTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aDoubleTapOpensOneTransferInvite() {
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = SettingsDest) {
                composable<SettingsDest> {}
                composable<InviteDest> {}
            }
        }
        rule.runOnIdle {
            nav.openTransferInvite()
            nav.openTransferInvite()
        }
        rule.runOnIdle {
            val invites = nav.currentBackStack.value.filter { it.destination.hasRoute<InviteDest>() }
            assertEquals(1, invites.size)
            assertEquals(InviteDest("PARENT", transfer = true), invites.single().toRoute<InviteDest>())
        }
    }
}
