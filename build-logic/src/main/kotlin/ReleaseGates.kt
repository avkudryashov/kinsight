import java.net.URI

/**
 * What a release artifact may not be built without (final review I-2, I-3): the manifest rules and a real privacy
 * policy address. Both gates hang on every task that produces an APK or an AAB, not only on `check`.
 */
object ReleaseGates {
    /** The Gradle property and local.properties key that carry the hosted policy address. */
    const val PRIVACY_URL_PROPERTY = "kinsight.privacyUrl"

    /** The environment variable read when neither the Gradle property nor local.properties has the address. */
    const val PRIVACY_URL_ENV = "KINSIGHT_PRIVACY_URL"

    /** The string in core/designsystem that :app overrides with the address (the library keeps a debug placeholder). */
    const val PRIVACY_URL_RESOURCE = "ds_privacy_policy_url"

    /** The tasks of [variantName] that produce a release APK or AAB: each must depend on the gates. */
    fun packagingTasks(variantName: String): Set<String> {
        val name = variantName.replaceFirstChar { it.uppercase() }
        return setOf("assemble$name", "bundle$name", "package$name", "package${name}Bundle", "sign${name}Bundle")
    }

    /** Why [url] cannot ship in a release build, or null when it can: https, a host, and no placeholder domain. */
    fun privacyUrlProblem(url: String?): String? {
        val hint = "set $PRIVACY_URL_PROPERTY in local.properties, pass -P$PRIVACY_URL_PROPERTY=<url> or export $PRIVACY_URL_ENV"
        if (url.isNullOrBlank()) return "the privacy policy URL is missing: $hint"
        if ("example" in url.lowercase()) return "the privacy policy URL is still a placeholder ($url): $hint"
        val uri = try {
            URI(url.trim())
        } catch (e: java.net.URISyntaxException) {
            return "the privacy policy URL is not a valid address ($url): ${e.reason}"
        }
        if (uri.scheme != "https") return "the privacy policy URL must use https ($url)"
        if (uri.host.isNullOrBlank()) return "the privacy policy URL has no host ($url)"
        return null
    }

    /** The address from the Gradle property, then local.properties, then the environment; null when none is set. */
    fun privacyUrl(gradleProperty: String?, localProperties: java.io.File, env: String?): String? {
        gradleProperty?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        if (localProperties.exists()) {
            val local = java.util.Properties().also { p -> localProperties.inputStream().use { p.load(it) } }
            local.getProperty(PRIVACY_URL_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        return env?.takeIf { it.isNotBlank() }?.trim()
    }

    /** The Yandex OAuth app (spec §2, §3): local.properties keys, Gradle properties of the same names override. */
    const val YANDEX_CLIENT_ID_PROPERTY = "kinsight.yandex.clientId"
    const val YANDEX_CLIENT_SECRET_PROPERTY = "kinsight.yandex.clientSecret"

    /** Set once the Android platform is registered with the release SHA-256 (spec §14): the yx<client_id>: redirect. */
    const val YANDEX_REDIRECT_PROPERTY = "kinsight.yandex.redirectUri"

    /** Spec §3: the redirect registered for the Web platform; the app takes the code by a paste field. */
    const val YANDEX_CODE_PAGE = "https://oauth.yandex.ru/verification_code"

    private val YANDEX_ID = Regex("^[0-9a-f]{32}$")
    private val YANDEX_SECRET = Regex("^[0-9A-Za-z]{16,64}$")

    /** [toString] never shows the secret. */
    class YandexClient(val clientId: String?, val clientSecret: String?, val redirectUri: String?) {
        override fun toString() = "YandexClient(clientId=$clientId, redirectUri=$redirectUri)"
    }

    /** Each value from the Gradle property, then local.properties (gitignored); never from the repository. */
    fun yandexClient(gradleProperty: (String) -> String?, localProperties: java.io.File): YandexClient {
        val local = java.util.Properties()
        if (localProperties.exists()) localProperties.inputStream().use { local.load(it) }
        fun value(name: String): String? =
            (gradleProperty(name)?.takeIf { it.isNotBlank() } ?: local.getProperty(name)?.takeIf { it.isNotBlank() })?.trim()
        return YandexClient(value(YANDEX_CLIENT_ID_PROPERTY), value(YANDEX_CLIENT_SECRET_PROPERTY), value(YANDEX_REDIRECT_PROPERTY))
    }

    /** Why a release cannot ship with [c], or null. The secret's value never appears in the text. */
    fun yandexClientProblem(c: YandexClient): String? {
        val hint = "set $YANDEX_CLIENT_ID_PROPERTY and $YANDEX_CLIENT_SECRET_PROPERTY in local.properties (never in git)"
        val id = c.clientId
        val secret = c.clientSecret
        if (id.isNullOrBlank()) return "the Yandex client id is missing: $hint"
        if (!YANDEX_ID.matches(id)) return "the Yandex client id is not 32 lowercase hex characters ($id): $hint"
        if (secret.isNullOrBlank()) return "the Yandex client secret is missing: $hint"
        if (!YANDEX_SECRET.matches(secret)) return "the Yandex client secret is malformed (its value is not shown): $hint"
        if (c.redirectUri != null && yandexRedirect(c) != c.redirectUri) {
            return "$YANDEX_REDIRECT_PROPERTY must be the yx$id: redirect the Yandex console shows for the Android platform (${c.redirectUri})"
        }
        return null
    }

    /** What BuildConfig gets: a well-formed value or "" (a build without the cloud). */
    fun yandexClientId(c: YandexClient): String = c.clientId?.takeIf { YANDEX_ID.matches(it) }.orEmpty()

    fun yandexClientSecret(c: YandexClient): String = c.clientSecret?.takeIf { YANDEX_SECRET.matches(it) }.orEmpty()

    /** The manifest scheme of the sign-in redirect; "yxunset" in a build without a client (it never matches a link). */
    fun yandexScheme(c: YandexClient): String = "yx" + yandexClientId(c).ifEmpty { "unset" }

    /** The configured app redirect when it is this client's yx scheme without quotes or spaces, else the verification page. */
    fun yandexRedirect(c: YandexClient): String {
        val id = yandexClientId(c)
        val r = c.redirectUri ?: return YANDEX_CODE_PAGE
        val clean = r.none { it == '"' || it == '\\' || it.isWhitespace() }
        return if (id.isNotEmpty() && clean && r.startsWith("yx$id:")) r else YANDEX_CODE_PAGE
    }
}
