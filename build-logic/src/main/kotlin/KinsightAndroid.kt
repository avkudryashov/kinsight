/** Android SDK levels shared by every Android module (spec §12, stage 3a). */
object KinsightAndroid {
    const val COMPILE_SDK = 36
    const val MIN_SDK = 31
    const val TARGET_SDK = 36

    /** Duplicate metadata from BouncyCastle and Kotlin jars that would otherwise break APK packaging. */
    val PACKAGING_EXCLUDES = setOf(
        "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        "/META-INF/{AL2.0,LGPL2.1}",
    )
}
