package app.kinsight.feature.onboarding

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The live camera for the invite QR: a CameraX preview plus an analyzer that runs ZXing ([QrDecode]) on each frame's
 * luminance, all on the device. Each new text goes to [onText] once, on the main thread, so a wrong code is reported
 * and the next one is still read. A camera that cannot be opened (none, busy, blocked by policy) calls [onUnavailable].
 * The camera is released when this leaves the composition.
 */
@Composable
internal fun QrScanner(onText: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val text by rememberUpdatedState(onText)
    val unavailable by rememberUpdatedState(onUnavailable)
    val analysis = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    // Unit: the executor and the binding live as long as this composition, never one without the other.
    DisposableEffect(Unit) {
        onDispose {
            providerFuture.addListener({
                runCatching { providerFuture.get().unbindAll() }
                analysis.shutdown()
            }, ContextCompat.getMainExecutor(context))
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val last = AtomicReference<String?>(null)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val frames = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    frames.setAnalyzer(analysis) { image ->
                        // Raw camera input: whatever ZXing throws skips this frame, never the process.
                        val found = runCatching { image.use { it.qrText() } }
                            .onFailure { Log.w(TAG, "frame skipped", it) }
                            .getOrNull()
                        if (found != null && last.getAndSet(found) != found) view.post { text(found) }
                    }
                    val camera = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, camera, preview, frames)
                } catch (e: Exception) {
                    Log.w(TAG, "camera unavailable", e)
                    unavailable()
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

/** The Y plane is the luminance ZXing needs (the analyzer's default output is YUV_420_888). */
private fun ImageProxy.qrText(): String? {
    val plane = planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return QrDecode.decode(bytes, plane.rowStride, width, height)
}

private const val TAG = "KinsightScanner"
