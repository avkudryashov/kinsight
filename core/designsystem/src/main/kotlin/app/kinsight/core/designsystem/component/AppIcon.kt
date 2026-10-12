package app.kinsight.core.designsystem.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * An app's icon from AppCatalog (a 48 px WebP), drawn at a fixed 40 dp; [modifier] applies inside that box.
 * Decorative (the label is next to it); a neutral circle when there is no icon or it does not decode.
 */
@Composable
fun AppIcon(webp: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(webp) {
        webp?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    val m = Modifier.size(40.dp).then(modifier)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = m)
    } else {
        Box(m.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
    }
}
