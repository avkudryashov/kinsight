package app.kinsight.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import app.kinsight.core.model.proto.Location as WireLocation

object LocationPermissions {
    enum class State { NONE, WHILE_IN_USE, ALWAYS }

    /** Precise location, with the approximate one Android 12+ asks for in the same request. */
    val FOREGROUND = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    fun foregroundGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun state(context: Context): State = when {
        !foregroundGranted(context) -> State.NONE
        context.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED -> State.ALWAYS
        else -> State.WHILE_IN_USE
    }
}

object Providers {
    /** The platform fused provider (never Play services), then GPS, then network; null when none is on. */
    fun pick(fused: Boolean, gps: Boolean, network: Boolean): String? = when {
        fused -> LocationManager.FUSED_PROVIDER
        gps -> LocationManager.GPS_PROVIDER
        network -> LocationManager.NETWORK_PROVIDER
        else -> null
    }
}

object Fixes {
    /**
     * Rulings L4, L5: through [FixCheck.of], so a non-finite or out-of-range location is dropped (null) and one without
     * accuracy is vague, never a precise ±0 m.
     */
    fun of(l: android.location.Location): Fix? = FixCheck.of(
        latDeg = l.latitude,
        lonDeg = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy else null,
        timeMs = l.time,
        provider = when (l.provider) {
            LocationManager.GPS_PROVIDER -> WireLocation.Provider.GPS
            LocationManager.NETWORK_PROVIDER -> WireLocation.Provider.NETWORK
            LocationManager.FUSED_PROVIDER -> WireLocation.Provider.FUSED
            LocationManager.PASSIVE_PROVIDER -> WireLocation.Provider.PASSIVE
            else -> WireLocation.Provider.PROVIDER_UNSPECIFIED
        },
    )
}

/** Spec §8: one fix through the system LocationManager (`getCurrentLocation`, API 30+), never Play services. */
class AndroidFixSource(context: Context) : FixSource {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)

    override fun ready(): Boolean = LocationPermissions.state(app) == LocationPermissions.State.ALWAYS

    @SuppressLint("MissingPermission") // checked right below
    override suspend fun fix(timeoutMs: Long): Fix? {
        if (!LocationPermissions.foregroundGranted(app)) return null
        fun on(p: String) = lm.isProviderEnabled(p)
        val provider = Providers.pick(
            fused = lm.hasProvider(LocationManager.FUSED_PROVIDER) && on(LocationManager.FUSED_PROVIDER),
            gps = on(LocationManager.GPS_PROVIDER),
            network = on(LocationManager.NETWORK_PROVIDER),
        ) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, app.mainExecutor) { l -> if (cont.isActive) cont.resume(l?.let(Fixes::of)) }
            }
        }
    }
}
