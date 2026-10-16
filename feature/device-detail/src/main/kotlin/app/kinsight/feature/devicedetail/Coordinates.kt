package app.kinsight.feature.devicedetail

import java.util.Locale
import kotlin.math.roundToInt

/** Coordinates as a map app copies them ("40.4168, -3.7038"): two dot-decimal numbers, comma, semicolon or space between. */
internal object Coordinates {
    private val SPLIT = Regex("""\s*[,;\s]\s*""")

    fun parse(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(SPLIT).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return (lat * 1e7).roundToInt() to (lon * 1e7).roundToInt()
    }

    fun format(latE7: Int, lonE7: Int): String = String.format(Locale.ROOT, "%.5f, %.5f", latE7 / 1e7, lonE7 / 1e7)
}
