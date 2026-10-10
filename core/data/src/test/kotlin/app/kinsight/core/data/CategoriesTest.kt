package app.kinsight.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoriesTest {
    private val unknown = "com.example.unknown"

    @Test
    fun everyApplicationInfoConstantMaps() {
        val expected = mapOf(
            0 to AppCategory.GAMES, 1 to AppCategory.AUDIO, 2 to AppCategory.VIDEO, 3 to AppCategory.PHOTOS,
            4 to AppCategory.SOCIAL, 5 to AppCategory.NEWS, 6 to AppCategory.MAPS, 7 to AppCategory.PRODUCTIVITY,
            8 to AppCategory.OTHER,
        )
        for ((c, cat) in expected) assertEquals(cat, Categories.of(unknown, c, emptyMap()), "category $c")
        // A constant from a newer Android than we know reads as Other.
        assertEquals(AppCategory.OTHER, Categories.of(unknown, 42, emptyMap()))
    }

    @Test
    fun phoneRunPackagesHaveTheirTableCategory() {
        // Pixel 8a / S23 run, 0.5.0: system gallery, camera, browser and messaging apps read as Other.
        val table = mapOf(
            "com.sec.android.gallery3d" to AppCategory.PHOTOS, "com.google.android.apps.photos" to AppCategory.PHOTOS,
            "com.sec.android.app.camera" to AppCategory.PHOTOS, "com.google.android.GoogleCamera" to AppCategory.PHOTOS,
            "com.google.android.apps.youtube.kids" to AppCategory.VIDEO,
            "com.sec.android.app.sbrowser" to AppCategory.PRODUCTIVITY, "com.yandex.browser" to AppCategory.PRODUCTIVITY,
            "com.google.android.apps.messaging" to AppCategory.SOCIAL, "com.samsung.android.messaging" to AppCategory.SOCIAL,
        )
        for ((pkg, cat) in table) {
            assertEquals(cat, Categories.of(pkg, -1, emptyMap()), pkg)
            assertEquals(cat, Categories.of(pkg, null, emptyMap()), pkg)
        }
        // Only for an undefined catalog category: a defined one still wins.
        assertEquals(AppCategory.GAMES, Categories.of("com.sec.android.gallery3d", 0, emptyMap()))
    }

    @Test
    fun undefinedOrAbsentFallsBackToTheTableThenOther() {
        val table = mapOf(
            "com.whatsapp" to AppCategory.SOCIAL, "org.telegram.messenger" to AppCategory.SOCIAL,
            "com.instagram.android" to AppCategory.SOCIAL, "com.zhiliaoapp.musically" to AppCategory.SOCIAL,
            "com.ss.android.ugc.trill" to AppCategory.SOCIAL, "com.snapchat.android" to AppCategory.SOCIAL,
            "com.facebook.katana" to AppCategory.SOCIAL, "com.discord" to AppCategory.SOCIAL,
            "com.vkontakte.android" to AppCategory.SOCIAL, "ru.oneme.app" to AppCategory.SOCIAL,
            "com.google.android.youtube" to AppCategory.VIDEO, "com.netflix.mediaclient" to AppCategory.VIDEO,
            "tv.twitch.android.app" to AppCategory.VIDEO,
            "com.spotify.music" to AppCategory.AUDIO, "com.google.android.apps.youtube.music" to AppCategory.AUDIO,
            "com.google.android.apps.maps" to AppCategory.MAPS, "ru.yandex.yandexmaps" to AppCategory.MAPS,
            "com.android.chrome" to AppCategory.PRODUCTIVITY, "com.google.android.gm" to AppCategory.PRODUCTIVITY,
            "com.google.android.apps.docs" to AppCategory.PRODUCTIVITY,
            "com.roblox.client" to AppCategory.GAMES, "com.mojang.minecraftpe" to AppCategory.GAMES,
            "com.supercell.brawlstars" to AppCategory.GAMES,
        )
        for ((pkg, cat) in table) {
            assertEquals(cat, Categories.of(pkg, -1, emptyMap()), pkg)
            assertEquals(cat, Categories.of(pkg, null, emptyMap()), pkg)
        }
        assertEquals(AppCategory.OTHER, Categories.of(unknown, -1, emptyMap()))
        assertEquals(AppCategory.OTHER, Categories.of(unknown, null, emptyMap()))
    }

    @Test
    fun aDefinedCatalogCategoryBeatsTheTable() {
        // The app said what it is: YouTube declaring itself a social app stays social.
        assertEquals(AppCategory.SOCIAL, Categories.of("com.google.android.youtube", 4, emptyMap()))
    }

    @Test
    fun anOverrideBeatsEverything() {
        val overrides = mapOf("com.roblox.client" to AppCategory.PRODUCTIVITY, unknown to AppCategory.NEWS)
        assertEquals(AppCategory.PRODUCTIVITY, Categories.of("com.roblox.client", 0, overrides))
        assertEquals(AppCategory.PRODUCTIVITY, Categories.of("com.roblox.client", -1, overrides))
        assertEquals(AppCategory.NEWS, Categories.of(unknown, null, overrides))
    }

    @Test
    fun theRemainderBucketIsAlwaysOther() {
        assertEquals(AppCategory.OTHER, Categories.of(Categories.REMAINDER_PACKAGE, null, emptyMap()))
        assertEquals(AppCategory.OTHER, Categories.of("~other", 0, mapOf("~other" to AppCategory.GAMES)))
    }
}
