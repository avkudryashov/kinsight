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
    // No TRY_HARDER (measured: it fixes none of the misses and costs time); the live analyzer uses STRATEGY_KEEP_ONLY_LATEST.
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // The last row needs only [width] bytes; anything shorter than that is not a frame.
        if (y.size < rowStride.toLong() * (height - 1) + width) return null
        val full = rowStride.toLong() * height
        return try {
            val data = if (y.size >= full) y else y.copyOf(full.toInt())
            // ZXing's detector misses some valid patterns at one scale and finds them at another (measured: 15 of 600
            // clean frames), so a frame that fails is retried on its 2x and 3x box-averaged copies before giving up.
            for (factor in SCALES) {
                val source = if (factor == 1) {
                    PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
                } else {
                    shrink(data, rowStride, width, height, factor)
                }
                try {
                    return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
                } catch (e: ReaderException) {
                    // next scale
                }
            }
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private val SCALES = intArrayOf(1, 2, 3)

    /** The frame averaged down by [factor]; null-size results cannot occur for QR-sized frames, and ZXing refuses tiny ones. */
    private fun shrink(y: ByteArray, rowStride: Int, width: Int, height: Int, factor: Int): PlanarYUVLuminanceSource {
        val w = width / factor
        val h = height / factor
        val out = ByteArray(w * h)
        val area = factor * factor
        for (r in 0 until h) for (c in 0 until w) {
            var sum = 0
            for (dr in 0 until factor) {
                val base = (r * factor + dr) * rowStride + c * factor
                for (dc in 0 until factor) sum += y[base + dc].toInt() and 0xff
            }
            out[r * w + c] = (sum / area).toByte()
        }
        return PlanarYUVLuminanceSource(out, w, h, 0, 0, w, h, false)
    }
}
