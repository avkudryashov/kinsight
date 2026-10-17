package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A small rounded label: the «лимит» mark on an app row, a limit's value (UI refresh §3, §4, §7). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.tertiaryContainer,
    content: Color = MaterialTheme.colorScheme.onTertiaryContainer,
) = Text(
    text,
    modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 2.dp),
    color = content,
    style = MaterialTheme.typography.labelMedium,
    maxLines = 1,
)
