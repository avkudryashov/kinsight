package app.kinsight.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GroupState
import app.kinsight.core.data.Role
import app.kinsight.core.designsystem.component.relativeTime
import app.kinsight.core.usage.UsageSettings

@Composable
fun TransparencyRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val group by graph.group.state.collectAsStateWithLifecycle(initialValue = GroupState.Loading)
    val usage by graph.settingsRepository.usageSettings.collectAsStateWithLifecycle(initialValue = UsageSettings())
    val ready = group as? GroupState.Ready
    if (ready == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        TransparencyScreen(ready, usage, System.currentTimeMillis(), onBack)
    }
}

/**
 * Spec §9.6 «прозрачность» in full: who receives this phone's data, what exactly, how to pause or stop, how long it
 * is kept. The list of parents is a snapshot, not the rule: every parent in the group receives a child's data,
 * including parents who join later, and the text says so.
 */
@Composable
fun TransparencyScreen(state: GroupState.Ready, usage: UsageSettings, nowMs: Long, onBack: () -> Unit) {
    val child = state.self.role == Role.CHILD
    val parents = state.members.filter { it.role == Role.PARENT && !it.isSelf }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tr_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (child) {
            Text(stringResource(R.string.tr_child_who), style = MaterialTheme.typography.titleMedium)
            if (parents.isEmpty()) Text(stringResource(R.string.tr_nobody))
            parents.forEach { p ->
                val seen = p.lastSeenMs?.let { stringResource(R.string.tr_seen, relativeTime(it, nowMs)) } ?: stringResource(R.string.tr_not_seen)
                Text("${p.name} · $seen")
            }
            Text(stringResource(R.string.tr_child_what))
            Text(stringResource(if (usage.sharing) R.string.tr_child_sharing_on else R.string.tr_child_sharing_off))
            Text(stringResource(R.string.tr_child_how))
            Text(stringResource(R.string.tr_child_keep))
        } else {
            Text(stringResource(R.string.tr_parent_own))
            Text(stringResource(R.string.tr_parent_sync, parents.joinToString(", ") { it.name }.ifEmpty { stringResource(R.string.tr_nobody) }))
            val detail = stringResource(R.string.set_days, usage.detailRetentionDays)
            val summary = stringResource(R.string.set_days, usage.summaryRetentionDays)
            Text(stringResource(R.string.tr_parent_keep, detail, summary))
        }
        Text(stringResource(R.string.tr_no_internet))
        TextButton(onBack) { Text(stringResource(R.string.set_back)) }
    }
}
