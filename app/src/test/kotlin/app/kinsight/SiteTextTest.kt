package app.kinsight

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Y5 T12 parked minors: the privacy pages stay true to the code and to the rulings' wording (P2, §15). */
class SiteTextTest {
    private fun page(lang: String) = File("../site/privacy-$lang.md").readText()

    @Test
    fun thePrivacyPagesKeepTheirAgreedWording() {
        for (lang in listOf("en", "ru", "es")) assertFalse("—" in page(lang), "$lang: no em dashes")
        assertTrue("около 48 часов" in page("ru"), "ruling P2")
        assertTrue("unas 48 horas" in page("es"), "ruling P2")
        assertTrue("about 48 hours" in page("en"), "ruling P2")
        // Spanish names the brand «Yandex Disk» and says «Disco» on its own (Y5 Task 12 review).
        assertEquals(emptyList(), Regex("""(?<!Yandex )\bDisk\b""").findAll(page("es")).map { it.range.first }.toList(), "es says «Disco»")
        assertTrue("no envía ningún dato a las tiendas" in page("es"), "the same claim as en/ru: nothing to the stores")
        // A PIN only guards what is done in Kinsight; clearing storage in Android settings is another way out.
        assertTrue("disconnected from the family in Kinsight" in page("en"))
        assertTrue("отключить от семьи в Kinsight" in page("ru"))
        assertTrue("desconectar el teléfono de la familia en Kinsight" in page("es"))
        // The refresh token is on the phone of whoever holds the storage now, which a take-over can change.
        assertTrue("parent who holds the storage" in page("en"))
    }
}
