package app.kinsight.core.designsystem

import app.kinsight.core.designsystem.component.qrMatrix
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrMatrixTest {
    @Test
    fun matrixDecodesBackToTheText() {
        val text = "kinsight://pair/1/" + "A".repeat(160)
        val m = qrMatrix(text)
        assertTrue(m.size >= 21 && m.all { it.size == m.size })
        val scale = 4
        val margin = 4 * scale
        val side = m.size * scale + 2 * margin
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in m.indices) for (x in m.indices) if (m[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[(margin + y * scale + dy) * side + margin + x * scale + dx] = 0xFF000000.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        assertEquals(text, QRCodeReader().decode(bitmap).text)
    }
}
