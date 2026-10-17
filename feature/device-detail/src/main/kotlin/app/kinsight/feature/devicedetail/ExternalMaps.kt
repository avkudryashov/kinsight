package app.kinsight.feature.devicedetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import app.kinsight.core.data.GeoPoint
import java.util.Locale

/** Offline map spec §5: the point in Yandex Maps or Google Maps, or any app that opens `geo:`. */
internal object ExternalMaps {
    const val YANDEX = "ru.yandex.yandexmaps"
    const val GOOGLE = "com.google.android.apps.maps"

    /** `pt` is longitude first. */
    fun yandexUri(latE7: Int, lonE7: Int): String =
        String.format(Locale.ROOT, "yandexmaps://maps.yandex.ru/?pt=%.7f,%.7f&z=17&l=map", lonE7 / 1e7, latE7 / 1e7)

    fun yandex(latE7: Int, lonE7: Int): Intent = Intent(Intent.ACTION_VIEW, yandexUri(latE7, lonE7).toUri()).setPackage(YANDEX)

    fun google(latE7: Int, lonE7: Int): Intent = Intent(Intent.ACTION_VIEW, geoUri(latE7, lonE7).toUri()).setPackage(GOOGLE)

    fun geo(latE7: Int, lonE7: Int): Intent = Intent(Intent.ACTION_VIEW, geoUri(latE7, lonE7).toUri())
}

/** The maps apps that can show a point, each null when nothing on the phone opens it (`<queries>` in the manifest). */
internal data class MapApps(val yandex: Intent?, val google: Intent?, val geo: Intent?) {
    /** Spec §4: a tap on the preview or the scheme opens Yandex, else Google, else any geo: app. */
    val preferred: Intent? get() = yandex ?: google ?: geo

    companion object {
        fun of(p: GeoPoint, pm: PackageManager): MapApps {
            fun Intent.opensIn(pkg: String) = takeIf { resolveActivity(pm)?.packageName == pkg }
            return MapApps(
                ExternalMaps.yandex(p.latE7, p.lonE7).opensIn(ExternalMaps.YANDEX),
                ExternalMaps.google(p.latE7, p.lonE7).opensIn(ExternalMaps.GOOGLE),
                ExternalMaps.geo(p.latE7, p.lonE7).takeIf { it.resolveActivity(pm) != null },
            )
        }
    }
}

/** Rulings L1: the point leaves this phone only here, on the parent's tap. */
internal fun openMaps(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The maps app went away since the check: nothing to open.
    }
}
