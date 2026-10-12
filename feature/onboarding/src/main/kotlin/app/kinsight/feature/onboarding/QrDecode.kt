package app.kinsight.feature.onboarding

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * The invite QR from one camera frame, on the device (ZXing core, no Play services, no network). [y] is the luminance
 * plane with [rowStride] bytes per row; CameraX may omit the padding after the last row, which is filled in here.
 * Null when the frame holds no readable QR code.
 */
internal object QrDecode {
    // No TRY_HARDER: this runs per frame; the live analyzer uses STRATEGY_KEEP_ONLY_LATEST, so a slow frame drops the rest.
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // The last row needs only [width] bytes; anything shorter than that is not a frame.
        if (y.size < rowStride.toLong() * (height - 1) + width) return null
        val full = rowStride.toLong() * height
        return try {
            val data = if (y.size >= full) y else y.copyOf(full.toInt())
            val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
