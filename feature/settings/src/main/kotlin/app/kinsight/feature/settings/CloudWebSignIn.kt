package app.kinsight.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kinsight.core.designsystem.component.dialogTestTags
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Yandex sign-in inside the app while the redirect is the verification page (spec §3): Yandex shows the code on that
 * page, and this screen reads it (from the address, else from the page) and hands it to [onCode], so nobody copies it.
 * An error in the address (the user refused) goes to [onRefused]. Only https Yandex pages are shown; any other page
 * opens outside the app ([onExternal]) or not at all. JavaScript runs here only (Yandex ID needs it), with no bridge to
 * the app; files are out of reach; cookies and storage are wiped when the screen closes, so no Yandex session stays in
 * the app. When the code cannot be read [FALLBACK_MS] after the verification page loaded, a field takes it by hand.
 * [webView] builds the view (tests capture it).
 */
@Composable
internal fun CloudWebSignIn(
    url: String,
    onCode: (String) -> Unit,
    onRefused: () -> Unit,
    onExternal: (String) -> Unit,
    onDismiss: () -> Unit,
    webView: (Context) -> WebView = ::WebView,
) {
    val code by rememberUpdatedState(onCode)
    val refused by rememberUpdatedState(onRefused)
    val external by rememberUpdatedState(onExternal)
    var codePage by remember(url) { mutableStateOf(false) }
    var fallback by remember(url) { mutableStateOf(false) }
    var typed by remember(url) { mutableStateOf("") }
    var view by remember { mutableStateOf<WebView?>(null) }
    val client = remember(url) {
        SignInWebClient(
            onCode = { code(it) },
            onRefused = { refused() },
            onExternal = { external(it) },
            onCodePage = { codePage = true },
        )
    }
    // The verification page without a code in its address: read the page until the code shows, the field after 5 s.
    LaunchedEffect(codePage) {
        if (!codePage) return@LaunchedEffect
        var waited = 0L
        while (!client.done) {
            // Only the verification page is read: a code-like text on another page is not the code.
            view?.takeIf { v -> v.url?.let(YandexCodePage::isCodePage) == true }?.evaluateJavascript(READ_PAGE) { result ->
                pageCode(result)?.let(client::fromPage)
            }
            delay(POLL_MS)
            waited += POLL_MS
            if (waited >= FALLBACK_MS) fallback = true
        }
    }
    Dialog(
        onDismissRequest = { view?.takeIf { it.canGoBack() }?.goBack() ?: onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().dialogTestTags().testTag("set_cloud_web")) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onDismiss, Modifier.testTag("set_cloud_web_close")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.set_cloud_cancel))
                    }
                    Text(stringResource(R.string.set_cloud_sign_in), style = MaterialTheme.typography.titleLarge)
                }
                AndroidView(
                    factory = { ctx -> webView(ctx).also { harden(it); it.webViewClient = client; view = it; it.loadUrl(url) } },
                    onRelease = { wipe(it); view = null },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (fallback) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.set_cloud_web_fallback))
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text(stringResource(R.string.set_cloud_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("set_cloud_code"),
                        )
                        TextButton({ onCode(typed) }, enabled = typed.isNotBlank(), modifier = Modifier.align(Alignment.End).testTag("set_cloud_done")) {
                            Text(stringResource(R.string.set_cloud_done))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Follows the sign-in's top-level pages. Addresses carry the code: nothing here logs them. [done] once the sign-in got
 * its answer (a code or an error), so a page loading late cannot answer twice.
 */
internal class SignInWebClient(
    private val onCode: (String) -> Unit,
    private val onRefused: () -> Unit,
    private val onExternal: (String) -> Unit,
    private val onCodePage: () -> Unit,
) : WebViewClient() {
    var done = false
        private set

    /** The code read off the verification page. */
    fun fromPage(code: String) {
        if (done) return
        done = true
        onCode(code)
    }

    /** True when [url] answered the sign-in (now or before): it is not shown. */
    private fun answered(url: String): Boolean {
        if (done) return true
        if (!YandexCodePage.isCodePage(url)) return false
        if (YandexCodePage.errorIn(url)) {
            done = true
            onRefused()
            return true
        }
        val code = YandexCodePage.codeFromUrl(url) ?: return false
        done = true
        onCode(code)
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        if (answered(url)) return true
        if (YandexCodePage.allowed(url)) return false
        if (YandexCodePage.external(url)) onExternal(url)
        return true
    }

    // A form post or the first load skips shouldOverrideUrlLoading: the same rules once the page starts.
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (url == null) return
        if (answered(url) || !YandexCodePage.allowed(url)) view.stopLoading()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url == null || answered(url)) return
        if (YandexCodePage.isCodePage(url)) onCodePage()
    }
}

private const val POLL_MS = 500L
private const val FALLBACK_MS = 5_000L

/** Visible leaf texts that could be the code, and the page's text: YandexCodePage.codeFromPage decides. */
private const val READ_PAGE = """(function() {
  var b = document.body; if (!b) return '';
  var all = b.getElementsByTagName('*'), out = [];
  for (var i = 0; i < all.length && out.length < 50; i++) {
    var e = all[i];
    if (e.children.length) continue;
    var t = ((e.tagName === 'INPUT' ? e.value : e.textContent) || '').trim();
    if (t.length < 6 || t.length > 64 || !e.getClientRects().length) continue;
    out.push(t);
  }
  return JSON.stringify({e: out, t: b.innerText || ''});
})()"""

/** The code in what [READ_PAGE] returned (a JSON string holding JSON), or null. */
internal fun pageCode(result: String?): String? {
    if (result == null) return null
    return try {
        val json = JSONTokener(result).nextValue() as? String
        if (json.isNullOrEmpty()) return null
        val o = JSONObject(json)
        val e = o.optJSONArray("e")
        val elements = if (e == null) emptyList() else (0 until e.length()).map { e.optString(it) }
        YandexCodePage.codeFromPage(elements, o.optString("t"))
    } catch (e: JSONException) {
        null
    }
}

@SuppressLint("SetJavaScriptEnabled") // Yandex ID needs it; only Yandex pages are shown and there is no JS bridge
private fun harden(v: WebView) {
    clearWebData(v.context)
    with(v.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        safeBrowsingEnabled = true
    }
}

/** The screen closed: nothing of the Yandex session stays (cookies, storage, cache, history, form data). */
private fun wipe(v: WebView) {
    v.stopLoading()
    v.webViewClient = WebViewClient()
    v.clearHistory()
    v.clearCache(true)
    v.clearFormData()
    clearWebData(v.context)
    v.destroy()
}

/** The app has no other WebView: its cookie jar and web storage are this sign-in's only. */
private fun clearWebData(ctx: Context) {
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword()
}
