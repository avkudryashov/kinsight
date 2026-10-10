package app.kinsight.feature.group

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.InviteState
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.theme.KinsightTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The route's lifetime rules on a real activity: stopping closes the invite, a configuration change does not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InviteRouteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class FakeInvite : InviteHandle {
        override val state = MutableStateFlow<InviteState>(InviteState.Showing("kinsight://pair/1/AAAA", Long.MAX_VALUE))
        var closes = 0

        override fun confirm() = Unit

        override fun decline() = Unit

        override fun close() {
            closes++
        }
    }

    private val invites = mutableListOf<FakeInvite>()
    private val opener = InviteOpener { _, _, _ -> FakeInvite().also { invites += it } }

    private fun show() = rule.setContent {
        val vm: InviteViewModel = viewModel { InviteViewModel(opener, { "Mom" }, Role.CHILD) }
        KinsightTheme(dynamicColor = false) { InviteRoute(vm, Role.CHILD, onClose = {}) }
    }

    @Test
    fun movingToCreatedClosesTheInviteAndComingBackOpensAFreshOne() {
        show()
        rule.waitForIdle()
        assertEquals(1, invites.size)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitForIdle()
        assertEquals(1, invites[0].closes)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals(2, invites.size)
        assertEquals(0, invites[1].closes)
    }

    @Test
    fun recreatingTheActivityKeepsTheInvite() {
        show()
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(1, invites.size)
        assertEquals(0, invites[0].closes)
    }
}
