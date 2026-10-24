package app.kinsight.feature.devicedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinsight.core.data.RuleMode
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.LimitBar

/**
 * The child's rules in one card (spec §9.7): how many blocks, limits and always-allowed apps there are, and where
 * today's time stands against the day limit ([todayMs], null when the screen is not on today). A tap opens the rules screen
 * ([onOpen]).
 */
@Composable
fun RulesCard(rules: List<ThresholdRule>, todayMs: Long?, onOpen: () -> Unit) {
    val dayLimit = rules.firstOrNull { it.target == ThresholdTarget.Total && it.mode == RuleMode.LIMIT }?.limitMs
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("det_rules"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.det_rules_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (rules.isEmpty()) {
                    stringResource(R.string.det_rules_none)
                } else {
                    stringResource(
                        R.string.det_rules_summary,
                        rules.count { it.mode == RuleMode.BLOCK },
                        rules.count { it.mode == RuleMode.LIMIT },
                        rules.count { it.mode == RuleMode.ALLOW },
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("det_rules_summary"),
            )
            if (dayLimit != null && todayMs != null) LimitBar(todayMs, dayLimit, Modifier.testTag("det_rules_bar"))
        }
    }
}
