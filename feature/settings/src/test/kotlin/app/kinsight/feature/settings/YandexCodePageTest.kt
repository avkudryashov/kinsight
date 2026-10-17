package app.kinsight.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexCodePageTest {
    private val page = "https://oauth.yandex.ru/verification_code"

    @Test
    fun theSignInRunsInTheAppOnlyForTheVerificationPage() {
        assertTrue(YandexCodePage.inApp("https://oauth.yandex.ru/authorize?response_type=code&client_id=x&redirect_uri=https%3A%2F%2Foauth.yandex.ru%2Fverification_code&state=s"))
        assertFalse(YandexCodePage.inApp("https://oauth.yandex.ru/authorize?response_type=code&client_id=x&redirect_uri=yx0000%3A%2F%2Fauthorize&state=s"))
        assertFalse(YandexCodePage.inApp("https://oauth.yandex.ru/authorize?response_type=code"))
    }

    @Test
    fun theVerificationPageIsRecognised() {
        assertTrue(YandexCodePage.isCodePage(page))
        assertTrue(YandexCodePage.isCodePage("$page?code=1234567&state=s"))
        assertTrue(YandexCodePage.isCodePage("https://OAUTH.yandex.ru/verification_code#code=1234567"))
        assertFalse("not https", YandexCodePage.isCodePage("http://oauth.yandex.ru/verification_code?code=1234567"))
        assertFalse(YandexCodePage.isCodePage("https://oauth.yandex.ru.evil.com/verification_code?code=1234567"))
        assertFalse(YandexCodePage.isCodePage("https://passport.yandex.ru/verification_code"))
        assertFalse(YandexCodePage.isCodePage("https://oauth.yandex.ru/authorize"))
        assertFalse(YandexCodePage.isCodePage("not a url"))
    }

    @Test
    fun theCodeIsReadFromTheQueryOrTheFragment() {
        assertEquals("1234567", YandexCodePage.codeFromUrl("$page?code=1234567&state=s"))
        assertEquals("1234567", YandexCodePage.codeFromUrl("$page#state=s&code=1234567"))
        assertEquals("abc123XYZ", YandexCodePage.codeFromUrl("$page?code=abc123XYZ"))
        assertNull(YandexCodePage.codeFromUrl(page))
        assertNull(YandexCodePage.codeFromUrl("$page?code="))
        assertNull("not a code", YandexCodePage.codeFromUrl("$page?code=12%3Cscript"))
        assertNull("a malformed escape", YandexCodePage.codeFromUrl("$page?code=%ZZ"))
    }

    @Test
    fun anErrorInTheAddressIsSeen() {
        assertTrue(YandexCodePage.errorIn("$page?error=access_denied&state=s"))
        assertTrue(YandexCodePage.errorIn("$page#error=access_denied"))
        assertFalse(YandexCodePage.errorIn("$page?code=1234567"))
    }

    @Test
    fun theCodeIsReadFromThePage() {
        // The one element that holds a code wins.
        assertEquals("1234567", YandexCodePage.codeFromPage(listOf("Continue", " 1234567 ", "Yandex"), "whatever"))
        assertEquals("the same code twice", "1234567", YandexCodePage.codeFromPage(listOf("1234567", "1234567"), ""))
        // Words, years and two different codes are not taken: the label decides.
        assertEquals("7654321", YandexCodePage.codeFromPage(listOf("Continue", "2026"), "Код подтверждения\n7654321\nСкопируйте его"))
        assertEquals("7654321", YandexCodePage.codeFromPage(listOf("1111111", "2222222"), "Confirmation code: 7654321"))
        assertEquals("ab12cd34", YandexCodePage.codeFromPage(emptyList(), "Your verification code ab12cd34 expires"))
        assertEquals("7654321", YandexCodePage.codeFromPage(emptyList(), "КОД ПОДТВЕРЖДЕНИЯ 7654321"))
        assertNull("too short", YandexCodePage.codeFromPage(emptyList(), "Код подтверждения 123"))
        assertNull(YandexCodePage.codeFromPage(listOf("Allow", "Cancel"), "Allow access to Kinsight?"))
        assertNull("too long", YandexCodePage.codeFromPage(emptyList(), "Confirmation code " + "1".repeat(65)))
    }

    @Test
    fun onlyYandexPagesOverHttpsAreShown() {
        for (ok in listOf(
            "https://oauth.yandex.ru/authorize?x=1", "https://passport.yandex.ru/auth", "https://sso.passport.yandex.ru/push",
            "https://id.yandex.com/", "https://sso.ya.ru/install", "https://ya.ru/", "https://passport.yandex.by/",
            "https://passport.yandex.com.tr/", "https://smartcaptcha.yandexcloud.net/checkbox", "https://PASSPORT.YANDEX.RU/",
        )) assertTrue(ok, YandexCodePage.allowed(ok))
        for (no in listOf(
            "http://passport.yandex.ru/auth", "https://evilyandex.ru/", "https://yandex.ru.evil.com/", "https://evil.com/?r=yandex.ru",
            "https://user@passport.yandex.ru/", "intent://passport.yandex.ru#Intent;end", "file:///sdcard/x.html", "javascript:alert(1)",
            "https://yandexcloud.net/", "https://accounts.google.com/", "not a url",
        )) assertFalse(no, YandexCodePage.allowed(no))
        assertTrue(YandexCodePage.external("https://accounts.google.com/"))
        assertTrue(YandexCodePage.external("http://passport.yandex.ru/"))
        assertFalse(YandexCodePage.external("intent://x#Intent;end"))
        assertFalse(YandexCodePage.external("javascript:alert(1)"))
    }
}
