package app.kinsight.feature.devicedetail

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kinsight.core.maps.Bounds
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** The street preview as MapLibre draws it (offline map spec §1, §4). */
internal val MapLibreSlot: StreetMapSlot = { style, frame, modifier -> MapLibrePreview(style, frame, modifier) }

/** Blocks hold z10..z14 (spec §2); MapLibre overzooms z14 up to the max without any request. */
internal const val PREVIEW_MIN_ZOOM = 10.0
internal const val PREVIEW_MAX_ZOOM = 17.0

/** Spec §4: a preview, not a map to move: no gesture at all, no logo, no compass; the attribution is drawn by Compose. */
internal fun previewOptions(context: Context): MapLibreMapOptions = MapLibreMapOptions.createFromAttributes(context)
    .textureMode(true) // a TextureView scrolls with the list; a SurfaceView would lag behind it
    .scrollGesturesEnabled(false)
    .horizontalScrollGesturesEnabled(false)
    .zoomGesturesEnabled(false)
    .doubleTapGesturesEnabled(false)
    .quickZoomGesturesEnabled(false)
    .rotateGesturesEnabled(false)
    .tiltGesturesEnabled(false)
    .compassEnabled(false)
    .logoEnabled(false)
    .attributionEnabled(false)
    .minZoomPreference(PREVIEW_MIN_ZOOM)
    .maxZoomPreference(PREVIEW_MAX_ZOOM)

@Composable
internal fun MapLibrePreview(style: String, frame: Bounds, modifier: Modifier) {
    val context = LocalContext.current
    val padding = with(LocalDensity.current) { 24.dp.roundToPx() }
    val view = remember(context) {
        MapLibre.getInstance(context)
        // Spec §1: nothing of the map reaches the network; the style links only asset:// and pmtiles://file://.
        MapLibre.setConnected(false)
        MapView(context, previewOptions(context))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> view.onCreate(null)
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        val memory = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) view.onLowMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = view.onLowMemory()
        }
        lifecycle.addObserver(observer) // replays ON_CREATE, ON_START, ON_RESUME up to the current state
        context.registerComponentCallbacks(memory)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            // The item left the list or the screen: undo what the lifecycle gave the view, in order.
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier) { v ->
        v.getMapAsync { map ->
            val camera = CameraUpdateFactory.newLatLngBounds(frame.latLngBounds(), padding)
            if (v.tag == style) {
                map.moveCamera(camera)
            } else {
                v.tag = style
                map.setStyle(Style.Builder().fromJson(style)) { map.moveCamera(camera) }
            }
        }
    }
}

private fun Bounds.latLngBounds(): LatLngBounds = LatLngBounds.Builder().include(LatLng(north, east)).include(LatLng(south, west)).build()
