package app.kinsight.feature.settings

import app.kinsight.core.data.CloudConfig
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The Yandex sign-in inside the app (CloudWebSignIn): which addresses the sign-in screen may show, and the code read off
 * the verification page (spec §3: until the app redirect is registered, Yandex shows the code on that page). Pure, so
 * the rules are tested without a WebView. Addresses here carry the code: nothing in this file logs them.
 */
internal object YandexCodePage {
    private const val CODE_HOST = "oauth.yandex.ru"
    private const val CODE_PATH = "/verification_code"

    /** Top-level pages the sign-in may show: Yandex ID, its passport and SSO pages, and the captcha. */
    private val DOMAINS = listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.by", "yandex.kz", "yandex.uz", "yandex.com.tr")
    private val HOSTS = setOf("smartcaptcha.yandexcloud.net")

    private val URL_CODE = Regex("^[0-9A-Za-z]{4,64}$")
    private val PAGE_CODE = Regex("^(?=[0-9A-Za-z]*[0-9])[0-9A-Za-z]{6,64}$")
    private val LABELLED = Regex("(?:Код подтверждения|Confirmation code|Verification code)\\s*:?\\s*([0-9A-Za-z]{6,64})(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)

    /** The authorize address [CloudConfig.CODE_PAGE] answers (no app redirect): the sign-in runs in the app. */
    fun inApp(authorizeUrl: String): Boolean = params(authorizeUrl)["redirect_uri"] == CloudConfig.CODE_PAGE

    /** The verification page Yandex lands on after the user allows (or refuses) the access. */
    fun isCodePage(url: String): Boolean {
        val u = uri(url) ?: return false
        return u.scheme.equals("https", true) && u.host.equals(CODE_HOST, true) && (u.rawPath ?: "").startsWith(CODE_PATH)
    }

    /** A page the sign-in screen may show: https on a Yandex host. Anything else opens outside the app, or not at all. */
    fun allowed(url: String): Boolean {
        val u = uri(url) ?: return false
        if (!u.scheme.equals("https", true) || u.userInfo != null) return false
        val host = u.host?.lowercase()?.trimEnd('.') ?: return false
        return host in HOSTS || DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** http or https elsewhere: the browser may open it. Other schemes (intent:, file:, javascript:) are dropped. */
    fun external(url: String): Boolean = uri(url)?.scheme?.lowercase() in setOf("http", "https")

    /** The code in the verification page's address, query or fragment. */
    fun codeFromUrl(url: String): String? = params(url)["code"]?.trim()?.takeIf { URL_CODE.matches(it) }

    /** The page answered with an error (the user refused or closed the request). */
    fun errorIn(url: String): Boolean = params(url).containsKey("error")

    /**
     * The code shown on the verification page: the one element whose whole text is a code ([elements], visible leaf
     * texts), else the token after the «Код подтверждения» / "Confirmation code" label in [text] (the page's innerText).
     */
    fun codeFromPage(elements: List<String>, text: String): String? {
        val found = elements.map { it.trim() }.filter { PAGE_CODE.matches(it) }.distinct()
        if (found.size == 1) return found.single()
        return LABELLED.find(text)?.groupValues?.get(1)
    }

    private fun uri(url: String): URI? = try {
        URI(url.trim()).takeIf { it.scheme != null }
    } catch (e: URISyntaxException) {
        null
    }

    private fun params(url: String): Map<String, String> {
        val u = uri(url) ?: return emptyMap()
        return listOfNotNull(u.rawQuery, u.rawFragment).flatMap { it.split('&') }.mapNotNull { kv ->
            val parts = kv.split('=', limit = 2)
            try {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toMap()
    }
}
