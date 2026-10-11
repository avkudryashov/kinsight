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
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val data = if (y.size >= rowStride * height) y else y.copyOf(rowStride * height)
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: ReaderException) {
            null
        }
    }
}
