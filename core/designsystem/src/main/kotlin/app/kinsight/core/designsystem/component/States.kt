package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A screen or section with nothing to show yet (UI refresh: an icon, a short title, one or two lines). */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Info,
    action: (@Composable () -> Unit)? = null,
) = Message(title, body, modifier, icon, action, isError = false)

/** Something went wrong, with what to do about it; TalkBack announces it politely when it appears. */
@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Warning,
    action: (@Composable () -> Unit)? = null,
) = Message(title, body, modifier, icon, action, isError = true)

@Composable
private fun Message(title: String, body: String, modifier: Modifier, icon: ImageVector, action: (@Composable () -> Unit)?, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .then(if (isError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(if (isError) colors.errorContainer else colors.primaryContainer).testTag("state_icon"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (isError) colors.onErrorContainer else colors.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = if (isError) colors.error else colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
