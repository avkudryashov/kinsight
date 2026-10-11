/** Android SDK levels shared by every Android module (spec §12, stage 3a). */
object KinsightAndroid {
    const val COMPILE_SDK = 36
    const val MIN_SDK = 31
    const val TARGET_SDK = 36

    /**
     * The upload-key properties named by `kinsight.upload.properties` in local.properties, or null when the
     * machine has none (CI, a fresh clone): the release build is then unsigned.
     */
    fun uploadSigning(localProperties: java.io.File): java.util.Properties? {
        if (!localProperties.exists()) return null
        val local = java.util.Properties().also { p -> localProperties.inputStream().use { p.load(it) } }
        val path = local.getProperty("kinsight.upload.properties") ?: return null
        val file = java.io.File(path).takeIf { it.isFile } ?: return null
        return java.util.Properties().also { p -> file.inputStream().use { p.load(it) } }
    }

    /** Duplicate metadata from BouncyCastle and Kotlin jars that would otherwise break APK packaging. */
    val PACKAGING_EXCLUDES = setOf(
        "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        "/META-INF/{AL2.0,LGPL2.1}",
    )
}
