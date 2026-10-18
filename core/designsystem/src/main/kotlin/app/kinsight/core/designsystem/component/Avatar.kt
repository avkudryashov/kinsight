package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinsight.core.designsystem.theme.OnChildCard

/** The first character of [name] in upper case, a whole code point (an emoji stays whole); «?» for a blank name. */
fun avatarLetter(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "?"
    return String(Character.toChars(n.codePointAt(0))).uppercase()
}

/**
 * A round letter avatar (UI refresh §3). Decorative: the name is always written next to it. The default colours suit
 * the child card's gradient (a darker disc keeps the white letter above 4.5:1).
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    container: Color = Color.Black.copy(alpha = 0.18f),
    content: Color = OnChildCard,
) {
    Box(modifier.size(size).clip(CircleShape).background(container).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(avatarLetter(name), color = content, style = MaterialTheme.typography.titleMedium)
    }
}
