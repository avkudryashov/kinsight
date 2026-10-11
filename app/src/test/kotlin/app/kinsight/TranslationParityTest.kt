package app.kinsight

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translatable English string of every module has a counterpart in each locale of locales_config.xml, with the
 * same placeholders, and no locale carries keys that English no longer has. Plain JVM: it reads the resource files.
 */
class TranslationParityTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")!!).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    private fun locales(): List<String> {
        val xml = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        return Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.filter { it != "en" }.toList()
    }

    private fun englishFiles(): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.path.endsWith("src/main/res/values/strings.xml".replace('/', File.separatorChar)) }
        .toList()

    /** name → placeholders per quantity ("" for a plain string); translatable="false" entries are skipped. */
    private fun read(file: File): Map<String, Map<String, List<String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, Map<String, List<String>>>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            val name = e.getAttribute("name")
            out[name] = when (e.tagName) {
                "string" -> mapOf("" to placeholders(e.textContent))
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to placeholders(item.textContent)
                    }
                }
                else -> continue
            }
        }
        return out
    }

    private fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyLocaleHasEveryStringWithTheSamePlaceholders() {
        val locales = locales()
        assertTrue("locales_config lists no translation", locales.isNotEmpty())
        val en = englishFiles()
        assertTrue("no English strings found under $root", en.size >= 9)
        val problems = mutableListOf<String>()
        for (enFile in en) {
            val source = read(enFile)
            for (locale in locales) {
                val file = File(enFile.parentFile!!.parentFile, "values-$locale/strings.xml")
                val module = enFile.relativeTo(root).path
                if (!file.isFile) {
                    if (source.isNotEmpty()) problems += "$module: no values-$locale"
                    continue
                }
                val target = read(file)
                for ((name, forms) in source) {
                    val t = target[name]
                    if (t == null) {
                        problems += "$locale $name: missing"
                        continue
                    }
                    if (forms.keys == setOf("")) {
                        if (t[""] != forms[""]) problems += "$locale $name: placeholders ${t[""]} != ${forms[""]}"
                    } else {
                        // Plural quantities differ by language (ru has few/many); each form keeps the source's placeholders.
                        if ("other" !in t) problems += "$locale $name: no quantity other"
                        val expected = forms.getValue("other")
                        for ((q, p) in t) if (p != expected) problems += "$locale $name[$q]: placeholders $p != $expected"
                    }
                }
                for (name in target.keys - source.keys) problems += "$locale $name: not in English"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
