package app.kinsight.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A screen or section with nothing to show yet (spec §9.8 «пустые состояния»). */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    Message(title, body, modifier, action, isError = false)

/** Something went wrong, with what to do about it; TalkBack announces it politely when it appears. */
@Composable
fun ErrorState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    Message(title, body, modifier, action, isError = true)

@Composable
private fun Message(title: String, body: String, modifier: Modifier, action: (@Composable () -> Unit)?, isError: Boolean) {
    Column(
        modifier
            .then(if (isError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
