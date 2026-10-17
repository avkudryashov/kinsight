package app.kinsight

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Release builds carry none of the adb hooks: every hook source lives in a debug source set (AGP compiles those into
 * debug only), and none is in main. The merged release manifest is checked by `:app:checkReleaseManifestRules`
 * (DebugGroupReceiver is a forbidden component there), so release has no entry point to them either.
 */
class DebugHooksLayoutTest {
    private val root = File("..")

    private val hooks = listOf(
        "app/src/debug/kotlin/app/kinsight/debug/DebugGroupReceiver.kt",
        "app/src/debug/kotlin/app/kinsight/debug/DebugCloud.kt",
        "app/src/debug/kotlin/app/kinsight/debug/DebugLocation.kt",
        "core/data/src/debug/kotlin/app/kinsight/core/data/CloudDebugDisk.kt",
        "core/data/src/debug/kotlin/app/kinsight/core/data/CloudDebugEnable.kt",
        "core/location/src/debug/kotlin/app/kinsight/core/location/LocationDebug.kt",
    )

    @Test
    fun everyHookIsInADebugSourceSetOnly() {
        assertEquals(emptyList(), hooks.filterNot { File(root, it).isFile }, "the hooks are where the debug build takes them")
        assertEquals(emptyList(), hooks.map { it.replace("/src/debug/", "/src/main/") }.filter { File(root, it).exists() })
        val names = listOf("DebugGroupReceiver", "CloudDebugDisk", "CloudDebugEnable", "LocationDebug", "CLOUD_FAKE_ENABLE", "LOCATION_STATUS")
        val leaks = listOf("app", "core/data", "core/location").flatMap { m ->
            File(root, "$m/src/main").walk().filter { it.isFile && it.extension in setOf("kt", "xml") }.flatMap { f ->
                val text = f.readText()
                // Mentions inside comments are allowed (main code points at its debug seam); code references are not.
                val code = text.lines().filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") || l.startsWith("<!--") } }
                names.filter { n -> code.any { n in it } }.map { "${f.path}: $it" }
            }.toList()
        }
        assertEquals(emptyList(), leaks)
    }
}
