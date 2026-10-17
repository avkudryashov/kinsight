package app.kinsight.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kinsight.core.designsystem.R
import app.kinsight.core.designsystem.theme.LimitBarColor
import app.kinsight.core.designsystem.theme.OnChildCard

/** Where today's time stands against a day limit. */
enum class LimitState { UNDER, REACHED, OVER }

/** [fraction] of the bar filled (0..1); [leftMs] still allowed; [overMs] past the limit. */
data class LimitProgress(val fraction: Float, val leftMs: Long, val overMs: Long, val state: LimitState)

/**
 * Today's [usedMs] against [limitMs]: OVER only from a whole minute past it (the text says «больше лимита на X»),
 * REACHED from the limit up to that minute, so neither «<1 мин left» nor «<1 мин over» is ever said (Review Focus 1).
 */
fun limitProgress(usedMs: Long, limitMs: Long): LimitProgress {
    val used = usedMs.coerceAtLeast(0L)
    val limit = limitMs.coerceAtLeast(0L)
    val over = used - limit
    val state = when {
        over >= 60_000L -> LimitState.OVER
        over >= 0L -> LimitState.REACHED
        else -> LimitState.UNDER
    }
    val fraction = if (limit == 0L) 1f else (used.toFloat() / limit).coerceIn(0f, 1f)
    return LimitProgress(fraction, (limit - used).coerceAtLeast(0L), over.coerceAtLeast(0L), state)
}

/**
 * The day limit's bar with its text («осталось X из Y», «лимит исчерпан», «больше лимита на X»), UI refresh §3, §7.
 * [onCard]: drawn on the child card's gradient (white text, translucent track); else on a light surface.
 */
@Composable
fun LimitBar(usedMs: Long, limitMs: Long, modifier: Modifier = Modifier, onCard: Boolean = false) {
    val p = limitProgress(usedMs, limitMs)
    val text = when (p.state) {
        LimitState.UNDER -> stringResource(R.string.ds_limit_left, durationText(p.leftMs), durationText(limitMs))
        LimitState.REACHED -> stringResource(R.string.ds_limit_reached)
        LimitState.OVER -> stringResource(R.string.ds_limit_over, durationText(p.overMs))
    }
    val track = if (onCard) OnChildCard.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (onCard) OnChildCard else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.semantics(mergeDescendants = true) {}.testTag("limit_bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(track)) {
            Box(Modifier.fillMaxWidth(p.fraction).fillMaxHeight().clip(CircleShape).background(LimitBarColor))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColor, modifier = Modifier.testTag("limit_bar_text"))
    }
}
