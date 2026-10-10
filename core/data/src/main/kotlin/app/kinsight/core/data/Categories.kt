package app.kinsight.core.data

/** Kinsight's own category set (spec §9.3, stage 5a); the screens name them, the order here means nothing. */
enum class AppCategory { GAMES, SOCIAL, VIDEO, AUDIO, PHOTOS, NEWS, MAPS, PRODUCTIVITY, OTHER }

/** A category's time over a detail range. */
data class CategoryTotal(val category: AppCategory, val totalMs: Long)

/**
 * Which [AppCategory] an app belongs to: the parent's local override first, then the child's `AppCatalog.category`
 * (`ApplicationInfo.category`, written raw by the catalog publisher), then for an undefined category a built-in table
 * of popular packages, else [AppCategory.OTHER]. The `~other` remainder bucket is always Other.
 *
 * Catalog values: the publisher writes `ApplicationInfo.category` as is, so `CATEGORY_UNDEFINED` arrives as -1 (a
 * non-default proto3 int32, so it survives the wire) and 0 really is `CATEGORY_GAME`. An app without a catalog entry
 * passes null and reads as undefined. The ints are literal: `core/data` unit tests run on the JVM.
 */
object Categories {
    /** The summary's reserved bucket for apps beyond the per-day limit. */
    const val REMAINDER_PACKAGE = "~other"

    private val BY_CONSTANT = mapOf(
        0 to AppCategory.GAMES, // CATEGORY_GAME
        1 to AppCategory.AUDIO, // CATEGORY_AUDIO
        2 to AppCategory.VIDEO, // CATEGORY_VIDEO
        3 to AppCategory.PHOTOS, // CATEGORY_IMAGE
        4 to AppCategory.SOCIAL, // CATEGORY_SOCIAL
        5 to AppCategory.NEWS, // CATEGORY_NEWS
        6 to AppCategory.MAPS, // CATEGORY_MAPS
        7 to AppCategory.PRODUCTIVITY, // CATEGORY_PRODUCTIVITY
        8 to AppCategory.OTHER, // CATEGORY_ACCESSIBILITY
    )

    private const val UNDEFINED = -1 // CATEGORY_UNDEFINED

    private val POPULAR: Map<String, AppCategory> = buildMap {
        listOf(
            "com.whatsapp", "org.telegram.messenger", "com.instagram.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.snapchat.android", "com.facebook.katana", "com.facebook.orca", "com.discord",
            "com.vkontakte.android", "ru.oneme.app", "com.twitter.android", "com.pinterest", "com.reddit.frontpage",
            "com.viber.voip", "jp.naver.line.android", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        ).forEach { put(it, AppCategory.SOCIAL) }
        listOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
            "com.google.android.apps.youtube.kids", "com.disney.disneyplus", "ru.rutube.app", "ru.kinopoisk",
        ).forEach { put(it, AppCategory.VIDEO) }
        listOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "ru.yandex.music", "com.soundcloud.android",
            "deezer.android.app",
        ).forEach { put(it, AppCategory.AUDIO) }
        listOf("com.google.android.apps.maps", "ru.yandex.yandexmaps", "com.waze", "ru.dublgis.dgismobile")
            .forEach { put(it, AppCategory.MAPS) }
        listOf(
            "com.android.chrome", "com.google.android.gm", "com.google.android.apps.docs",
            "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.classroom", "com.microsoft.office.word", "com.microsoft.teams", "org.mozilla.firefox",
            "com.duolingo", "com.sec.android.app.sbrowser", "com.yandex.browser",
        ).forEach { put(it, AppCategory.PRODUCTIVITY) }
        listOf(
            "com.roblox.client", "com.mojang.minecraftpe", "com.supercell.brawlstars", "com.supercell.clashroyale",
            "com.supercell.clashofclans", "com.kiloo.subwaysurf", "com.dts.freefireth", "com.activision.callofduty.shooter",
            "com.tencent.ig", "com.innersloth.spacemafia",
        ).forEach { put(it, AppCategory.GAMES) }
        listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.sec.android.app.camera",
            "com.google.android.GoogleCamera",
        ).forEach { put(it, AppCategory.PHOTOS) }
    }

    fun of(packageName: String, catalogCategory: Int?, overrides: Map<String, AppCategory>): AppCategory {
        if (packageName == REMAINDER_PACKAGE) return AppCategory.OTHER
        overrides[packageName]?.let { return it }
        if (catalogCategory != null && catalogCategory != UNDEFINED) return BY_CONSTANT[catalogCategory] ?: AppCategory.OTHER
        return POPULAR[packageName] ?: AppCategory.OTHER
    }
}
