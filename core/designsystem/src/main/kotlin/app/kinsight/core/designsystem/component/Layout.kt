package app.kinsight.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The widest a screen's content gets (U8, spec §9.8): on a tablet or an unfolded phone it stays a readable column. */
val MaxContentWidth = 640.dp

/** [content] at most [MaxContentWidth] wide, centred horizontally, filling the height; a phone keeps its whole width. */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize(), content = content)
    }
}
