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
    private val unescapedApostrophe = Regex("""(?<!\\)'""")

    private fun locales(): List<String> {
        val xml = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        return Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.filter { it != "en" }.toList()
    }

    /** BCP 47 tag of locales_config → resource qualifier: "es" → "es", "es-ES" → "es-rES", "zh-Hant-TW" → "b+zh+Hant+TW". */
    private fun qualifier(tag: String): String {
        val parts = tag.split('-')
        return when {
            parts.size == 1 -> tag
            parts.size == 2 && parts[1].length == 2 -> "${parts[0]}-r${parts[1].uppercase()}"
            else -> "b+" + parts.joinToString("+")
        }
    }

    private fun englishFiles(): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.path.endsWith("src/main/res/values/strings.xml".replace('/', File.separatorChar)) }
        .toList()

    /**
     * name → placeholders per form: "" for a plain string, the quantity for a plural, "[i]" for a string-array item.
     * translatable="false" entries are skipped; an unescaped apostrophe is reported into [problems].
     */
    private fun read(file: File, problems: MutableList<String>): Map<String, Map<String, List<String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, Map<String, List<String>>>()
        val children = doc.documentElement.childNodes
        fun text(name: String, e: Element): String = e.textContent.also {
            if (unescapedApostrophe.containsMatchIn(it)) problems += "${file.relativeTo(root).path} $name: unescaped '"
        }
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            val name = e.getAttribute("name")
            val items = e.getElementsByTagName("item")
            out[name] = when (e.tagName) {
                "string" -> mapOf("" to placeholders(text(name, e)))
                "plurals" -> (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to placeholders(text(name, item))
                }
                "string-array" -> (0 until items.length).associate { "[$it]" to placeholders(text(name, items.item(it) as Element)) }
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
            val source = read(enFile, problems)
            for (locale in locales) {
                val dir = "values-${qualifier(locale)}"
                val file = File(enFile.parentFile!!.parentFile, "$dir/strings.xml")
                val module = enFile.relativeTo(root).path
                if (!file.isFile) {
                    if (source.isNotEmpty()) problems += "$module: no $dir"
                    continue
                }
                val target = read(file, problems)
                for ((name, forms) in source) {
                    val t = target[name]
                    if (t == null) {
                        problems += "$locale $name: missing"
                        continue
                    }
                    if (forms.keys == setOf("") || forms.keys.all { it.startsWith("[") }) {
                        // A plain string, or a string-array: same items, same placeholders in each.
                        if (t != forms) problems += "$locale $name: $t != $forms"
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
