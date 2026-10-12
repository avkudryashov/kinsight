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
}
