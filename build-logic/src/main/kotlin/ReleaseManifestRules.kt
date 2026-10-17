import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the release (Play) manifest must and must not contain (audit S-M4, A-10):
 * - no debug entry points;
 * - the monitoring flag;
 * - no ads;
 * - nothing of Google Play services, ML Kit or Firebase: the app works offline and without Play services;
 * - no camera requirement (a phone without one joins by link). The CAMERA permission itself is allowed.
 *
 * Allowed on purpose: the network since cloud Y2 (spec §2: sync through the user's own Yandex Disk, off by default) and
 * location since cloud Y4 (spec §12: the child's position for the family's safe zones, through the platform
 * LocationManager only).
 */
object ReleaseManifestRules {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    private val FORBIDDEN_COMPONENTS = setOf(
        "androidx.compose.ui.tooling.PreviewActivity",
        "androidx.activity.ComponentActivity",
        "app.kinsight.debug.DebugGroupReceiver",
    )

    private val FORBIDDEN_PERMISSIONS = setOf(
        "android.permission.QUERY_ALL_PACKAGES",
        "com.google.android.gms.permission.AD_ID",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.USE_EXACT_ALARM",
    )

    private val FORBIDDEN_PREFIXES = listOf(
        "com.google.android.gms.",
        "com.google.mlkit.",
        "com.google.firebase.",
        "com.google.android.datatransport.",
    )

    fun violations(manifestXml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestXml.byteInputStream())
        fun elements(tag: String): List<Element> = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        fun Element.android(attr: String): String? = getAttributeNS(ANDROID, attr).takeIf { it.isNotEmpty() }
        fun google(name: String) = FORBIDDEN_PREFIXES.any { name.startsWith(it) }

        val out = mutableListOf<String>()
        if (elements("application").any { it.android("debuggable") == "true" }) out += "application is debuggable"
        for (tag in listOf("activity", "activity-alias", "receiver", "service", "provider")) {
            for (e in elements(tag)) {
                val name = e.android("name") ?: continue
                if (name in FORBIDDEN_COMPONENTS) out += "$tag $name must not ship in release"
                if (google(name)) out += "$tag $name comes from Google Play services, ML Kit or Firebase"
            }
        }
        for (e in elements("meta-data")) {
            val name = e.android("name") ?: continue
            if (google(name)) out += "meta-data $name comes from Google Play services, ML Kit or Firebase"
        }
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            for (e in elements(tag)) {
                val name = e.android("name") ?: continue
                if (name in FORBIDDEN_PERMISSIONS) out += "permission $name is forbidden"
            }
        }
        for (e in elements("uses-feature")) {
            val name = e.android("name") ?: continue
            if (name.startsWith("android.hardware.camera") && e.android("required") != "false") out += "feature $name must not be required"
        }
        val flag = elements("meta-data").any { it.android("name") == "isMonitoringTool" && it.android("value") == "child_monitoring" }
        if (!flag) out += "meta-data isMonitoringTool=child_monitoring is missing"
        return out
    }
}
