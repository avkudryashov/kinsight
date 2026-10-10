package app.kinsight.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinsight.core.designsystem.R

/**
 * Period navigation: previous / next arrows (`IconButton`s with content descriptions, mirrored in RTL) around the
 * period [title]. The buttons are sized to 48 dp explicitly: Material 3 1.5.0-alpha18 lays an `IconButton` out at
 * 40 dp. The title is a polite live region, so TalkBack announces the new period after an arrow tap.
 */
@Composable
fun PeriodHeader(
    title: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp).testTag("det_prev"), enabled = canBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.ds_period_previous))
        }
        Text(
            title,
            Modifier.weight(1f).testTag("det_period_title").semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        IconButton(onForward, Modifier.size(48.dp).testTag("det_next"), enabled = canForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.ds_period_next))
        }
    }
}
