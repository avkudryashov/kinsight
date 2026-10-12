package app.kinsight.core.usage

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream

class PackageAppInfoSource(private val pm: PackageManager) : AppInfoSource {
    override fun info(packageName: String): AppInfo? {
        @Suppress("DEPRECATION") // the ApplicationInfoFlags overload needs API 33; minSdk is 31
        val ai = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return AppInfo(
            label = pm.getApplicationLabel(ai).toString(),
            iconWebp = IconEncoder.webp(pm.getApplicationIcon(ai)),
            category = ai.category,
        )
    }
}

/** 48 × 48 px lossless WebP (spec §4.3): deterministic, so an unchanged icon keeps its catalog hash. */
object IconEncoder {
    const val SIZE_PX = 48

    fun webp(icon: Drawable): ByteArray {
        val drawable = icon.constantState?.newDrawable()?.mutate() ?: icon
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "WebP encoding failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
