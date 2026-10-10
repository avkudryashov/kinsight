package app.kinsight.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.kinsight.core.designsystem.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Modules of the QR code of [text] (error correction M), without the quiet zone.
 * Throws [IllegalArgumentException] for empty text and [WriterException] for text too long for a QR code.
 */
fun qrMatrix(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
}

/**
 * Always black on white whatever the theme (scanners expect it), with a white quiet zone of at least 24 dp and
 * 4 modules, and modules snapped to whole pixels. Text that cannot be encoded shows an error state instead.
 */
@Composable
fun QrCode(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        try {
            qrMatrix(text)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: WriterException) {
            null
        }
    }
    if (matrix == null) {
        ErrorState(stringResource(R.string.ds_qr_error_title), stringResource(R.string.ds_qr_error_body), modifier)
        return
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .background(Color.White)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        val side = minOf(size.width, size.height).toInt()
        val layout = qrLayout(side, matrix.size, 24.dp.roundToPx())
        val cell = layout.cellPx.toFloat()
        val origin = layout.offsetPx.toFloat()
        for (y in matrix.indices) for (x in matrix.indices) {
            if (matrix[y][x]) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
        }
    }
}

/** Whole-pixel module size and the offset of the first module inside a square of [sidePx]. */
internal data class QrLayout(val cellPx: Int, val offsetPx: Int)

/** The largest whole-pixel module that leaves a quiet zone of at least [minQuietPx] and 4 modules on each side. */
internal fun qrLayout(sidePx: Int, modules: Int, minQuietPx: Int): QrLayout {
    val cell = minOf((sidePx - 2 * minQuietPx) / modules, sidePx / (modules + 8)).coerceAtLeast(1)
    return QrLayout(cell, (sidePx - modules * cell) / 2)
}
