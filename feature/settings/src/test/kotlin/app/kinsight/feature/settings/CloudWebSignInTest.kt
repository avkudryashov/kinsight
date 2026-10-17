package app.kinsight.feature.settings

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kinsight.core.data.CloudStatus
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.Prefs
import app.kinsight.core.data.Role
import app.kinsight.core.data.ServiceStatus
import app.kinsight.core.data.SignInState
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.GroupId
import app.kinsight.core.transport.ble.ServiceState
import app.kinsight.core.usage.UsageSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The in-app Yandex sign-in (CloudWebSignIn) inside the settings screen, as the route wires it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CloudWebSignInTest {
    @get:Rule
    val rule = createComposeRule()

    private val authorize =
        "https://oauth.yandex.ru/authorize?response_type=code&redirect_uri=https%3A%2F%2Foauth.yandex.ru%2Fverification_code&state=s"
    private val parent = MemberView(DeviceId("aa".repeat(16)), "Mom", Role.PARENT, isAdmin = true, isSelf = true, lastSeenMs = 1L)
    private val ui = mutableStateOf(
        SettingsUi(
            GroupState.Ready(GroupId("dd".repeat(16)), parent, listOf(parent)), UsageSettings(), Prefs(),
            ServiceStatus(ServiceState.RUNNING, false, null, emptyList(), false, null),
            cloud = CloudStatus(available = true, on = true, ownerName = "Mom", selfIsOwner = true, lastSyncMs = 1L),
            signIn = SignInState.Waiting(authorize),
        ),
    )
    private var web: WebView? = null
    private val codes = mutableListOf<String>()
    private val refusals = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var dismissed = 0

    /** The in-app sign-in on [state]; a code moves it to Working, as CloudSignIn.finish does. */
    private fun show(state: MutableState<SettingsUi> = ui) = rule.setContent {
        KinsightTheme() {
            SettingsScreen(
                state.value, onSharing = {}, onConsent = {}, onStopSync = {}, onStartSync = {},
                onRetention = { _, _ -> }, onBattery = {}, onDiagnostics = {},
                onResetIdentity = {}, nowMs = 2L,
                onCloudCode = {
                    codes += it
                    state.value = state.value.copy(signIn = SignInState.Working)
                },
                onCloudDismiss = { dismissed++ },
                cloudWeb = authorize, onCloudRefused = { refusals += "refused" }, onCloudExternal = { opened += it },
                webView = { ctx -> WebView(ctx).also { web = it } },
            )
        }
    }

    /** A top-level navigation the page asks for; true when the sign-in screen kept it from loading. */
    private fun navigate(url: String): Boolean {
        var handled = false
        rule.runOnUiThread {
            val w = web!!
            val request = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            handled = w.webViewClient.shouldOverrideUrlLoading(w, request)
        }
        rule.waitForIdle()
        return handled
    }

    @Test
    fun theVerificationPageHandsItsCodeOverAndCloses() {
        show()
        rule.onNodeWithTag("set_cloud_web").assertExists()
        rule.onNodeWithText("Confirm the access in the browser", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_code").assertDoesNotExist()
        val w = web!!
        assertEquals(authorize, shadowOf(w).lastLoadedUrl)
        assertTrue(w.settings.javaScriptEnabled)
        assertFalse(w.settings.allowFileAccess)
        assertFalse(w.settings.allowContentAccess)
        rule.runOnUiThread { CookieManager.getInstance().setCookie("https://passport.yandex.ru", "Session_id=x") }
        assertFalse("Yandex ID pages load", navigate("https://passport.yandex.ru/auth?retpath=x"))
        assertTrue(codes.isEmpty())
        assertTrue(navigate("https://oauth.yandex.ru/verification_code?code=1234567&state=s"))
        assertEquals(listOf("1234567"), codes)
        rule.onNodeWithTag("set_cloud_web").assertDoesNotExist()
        rule.onNodeWithText("Connecting…").assertExists()
        rule.runOnUiThread { assertNull("no Yandex session stays", CookieManager.getInstance().getCookie("https://passport.yandex.ru")) }
    }

    @Test
    fun aRefusalOnTheYandexPageEndsTheSignIn() {
        show()
        assertTrue(navigate("https://oauth.yandex.ru/verification_code?error=access_denied&state=s"))
        assertEquals(listOf("refused"), refusals)
        assertTrue(codes.isEmpty())
    }

    @Test
    fun otherPagesOpenOutsideTheApp() {
        show()
        assertTrue(navigate("https://accounts.google.com/signin"))
        assertTrue(navigate("http://passport.yandex.ru/auth"))
        assertTrue(navigate("intent://x#Intent;end"))
        assertEquals(listOf("https://accounts.google.com/signin", "http://passport.yandex.ru/auth"), opened)
        rule.onNodeWithTag("set_cloud_web").assertExists()
    }

    @Test
    fun closingTheScreenCancelsTheSignIn() {
        show()
        rule.onNodeWithTag("set_cloud_web_close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aCodeTheEndpointDidNotTakeWaitsInThePasteField() {
        show()
        navigate("https://oauth.yandex.ru/verification_code#code=1234567")
        ui.value = ui.value.copy(signIn = SignInState.Waiting(authorize, networkError = true))
        rule.waitForIdle()
        rule.onNodeWithTag("set_cloud_web").assertDoesNotExist()
        rule.onNodeWithTag("set_cloud_code").assertTextContains("1234567")
        rule.onNodeWithTag("set_cloud_done").performClick()
        assertEquals(listOf("1234567", "1234567"), codes)
    }

    @Test
    fun aCodeThePageDoesNotGiveUpIsTypedAfterFiveSeconds() {
        show()
        rule.runOnUiThread { web!!.webViewClient.onPageFinished(web!!, "https://oauth.yandex.ru/verification_code") }
        rule.waitForIdle()
        val since = rule.mainClock.currentTime
        assertTrue(rule.onAllNodesWithTag("set_cloud_code").fetchSemanticsNodes().isEmpty())
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("set_cloud_code").fetchSemanticsNodes().isNotEmpty() }
        val waited = rule.mainClock.currentTime - since
        assertTrue("the field after 5 s: $waited", waited in 4_500..6_000)
        rule.onNodeWithTag("set_cloud_code").performTextInput("7654321")
        rule.onNodeWithTag("set_cloud_done").performClick()
        assertEquals(listOf("7654321"), codes)
    }

    @Test
    fun theCodeIsReadFromWhatThePageScriptReturned() {
        val elements = JSONObject().put("e", JSONArray(listOf("Continue", "1234567"))).put("t", "").toString()
        assertEquals("1234567", pageCode(JSONObject.quote(elements)))
        val label = JSONObject().put("e", JSONArray()).put("t", "Confirmation code\n7654321").toString()
        assertEquals("7654321", pageCode(JSONObject.quote(label)))
        assertNull(pageCode("null"))
        assertNull(pageCode("\"\""))
        assertNull(pageCode("not json"))
        assertNull(pageCode(null))
    }
}
