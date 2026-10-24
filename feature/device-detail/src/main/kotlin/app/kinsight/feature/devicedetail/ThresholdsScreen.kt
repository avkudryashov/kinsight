package app.kinsight.feature.devicedetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.RuleMode
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.LimitBar
import app.kinsight.core.designsystem.component.Pill
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The daily limits of child [id] (spec §9.7, stage 5b), opened from the child's detail on a parent's phone. */
@Composable
fun ThresholdsRoute(id: DeviceId, onBack: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: ThresholdsViewModel = viewModel(key = "thresholds-${id.hex}") { ThresholdsViewModel(graph.usage, id, graph.scope, createSavedStateHandle(), graph.settingsRepository.usageSettings.map { !it.blockingConsented }.catch { emit(false) }) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pendingDeletes.collectAsStateWithLifecycle()
    state?.let {
        ThresholdsScreen(
            it,
            pending = pending,
            onSave = { vm.save(it) },
            onDelete = vm::delete,
            onUndo = { r -> vm.undo(r) },
            onCommitDelete = { r -> vm.commitDelete(r) },
            onOffered = vm::offered,
            onBack = onBack,
        )
    } ?: Loading()
}

/**
 * The child's rules: the day total on its card, then «Blocked», «Limits» and «Always allowed» (a section without rules is
 * not drawn); tap a rule to change it, delete with «Undo» in a snackbar. «Add rule» asks what to do first (limit, block,
 * always allow), then the day total, a category or one of the child's apps of the last 30 days (searchable), and for a
 * limit the time in 15-minute steps (−/+ buttons, the value read aloud as it changes). [onDelete] only hides a rule; each id in [pending] (deleted, not
 * yet committed) has its snackbar, also after a recreation: [onCommitDelete] follows when it goes without «Undo»
 * ([onUndo]). A child's phone only reads them ([ThresholdsState.canEdit]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdsScreen(
    state: ThresholdsState,
    pending: Set<String>,
    onSave: (ThresholdRule) -> Unit,
    onDelete: (ThresholdRule) -> Unit,
    onUndo: (String) -> Unit,
    onCommitDelete: (String) -> Unit,
    onBack: () -> Unit,
    onOffered: (String) -> Unit = {},
) {
    // Our own queue in front of the host's: [onOffered] runs right before a snackbar is actually shown.
    val queue = remember { Mutex() }
    val offeredNow by rememberUpdatedState(onOffered)
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addingTotal by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val removed = stringResource(R.string.thr_deleted)
    val undo = stringResource(R.string.thr_undo)
    // One snackbar per pending delete, queued by the host. Leaving the screen cancels them (the view model commits
    // what is left); a rotation shows them again.
    val undoNow by rememberUpdatedState(onUndo)
    val commitNow by rememberUpdatedState(onCommitDelete)
    for (id in pending) {
        key(id) {
            LaunchedEffect(id) {
                val result = queue.withLock {
                    offeredNow(id)
                    snackbar.showSnackbar(removed, actionLabel = undo, duration = SnackbarDuration.Long)
                }
                if (result == SnackbarResult.ActionPerformed) undoNow(id) else commitNow(id)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.thr_title, state.childName)) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.det_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("thr_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    if (state.canEdit) stringResource(R.string.thr_subtitle) else stringResource(R.string.thr_parent_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("thr_subtitle"),
                )
            }
            if (state.blockingOff) {
                item(key = "blocking_off") { Warning(stringResource(R.string.thr_blocking_off), "thr_blocking_off") }
            }
            if (state.awaitingConsent) {
                item(key = "awaiting_consent") { Warning(stringResource(R.string.thr_awaiting_consent), "thr_awaiting_consent") }
            }
            item(key = "total") {
                TotalCard(
                    rule = state.rules.firstOrNull { it.target == ThresholdTarget.Total },
                    todayMs = state.todayMs,
                    canEdit = state.canEdit,
                    onEdit = { editing = it },
                    onSet = { addingTotal = true },
                    onSave = onSave,
                    onDelete = onDelete,
                )
            }
            val separate = state.rules.filter { it.target != ThresholdTarget.Total }
            if (separate.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.thr_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            for ((mode, title, tag) in SECTIONS) {
                val inSection = separate.filter { it.mode == mode }
                if (inSection.isEmpty()) continue
                item(key = "section_${mode.name}") { SectionTitle(stringResource(title), Modifier.testTag(tag)) }
                items(inSection, key = { it.id }) { r -> RuleRow(r, state, onEdit = { editing = r.id }, onDelete = onDelete) }
            }
            if (state.canEdit) {
                item(key = "add") {
                    FilledTonalButton({ adding = true }, Modifier.heightIn(min = 48.dp).testTag("thr_add")) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.thr_add), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    val edited = state.rules.firstOrNull { it.id == editing }
    if (adding || addingTotal || edited != null) {
        val fixed = edited?.target ?: ThresholdTarget.Total.takeIf { addingTotal }
        ThresholdDialog(
            title = if (fixed != null) targetName(fixed, state::appLabel) else stringResource(R.string.thr_new),
            child = state.child,
            fixedTarget = fixed,
            existing = state.rules,
            apps = state.apps,
            onDismiss = {
                adding = false
                addingTotal = false
                editing = null
            },
            onSave = {
                adding = false
                addingTotal = false
                editing = null
                onSave(it)
            },
        )
    }
}

/** The sections below the day total, in this order, each with the title of its mode. */
private val SECTIONS = listOf(
    Triple(RuleMode.BLOCK, R.string.thr_section_blocked, "thr_section_blocked"),
    Triple(RuleMode.LIMIT, R.string.thr_section_limits, "thr_section_limits"),
    Triple(RuleMode.ALLOW, R.string.thr_section_allowed, "thr_section_allowed"),
)

/** A line the parent should read before the rules: why blocks do not act yet. */
@Composable
private fun Warning(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}

/** One rule: its target and a pill by mode (the limit, «block» when it blocks at the limit, «blocked», «always»); tap to change, bin to delete. */
@Composable
private fun RuleRow(r: ThresholdRule, state: ThresholdsState, onEdit: () -> Unit, onDelete: (ThresholdRule) -> Unit) {
    val name = targetName(r.target, state::appLabel)
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (r.mode) {
                    RuleMode.LIMIT -> {
                        Pill(
                            durationText(r.limitMs),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (r.blockOnLimit) {
                            Pill(
                                stringResource(R.string.thr_pill_block),
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    RuleMode.BLOCK -> Pill(
                        stringResource(R.string.thr_pill_blocked),
                        Modifier.testTag("thr_pill_blocked"),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    RuleMode.ALLOW -> Pill(
                        stringResource(R.string.thr_pill_allowed),
                        Modifier.testTag("thr_pill_allowed"),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (state.canEdit) {
                    IconButton(
                        onClick = { onDelete(r) },
                        modifier = Modifier.size(48.dp).testTag("thr_delete_${r.id}"),
                    ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.thr_delete, name)) }
                }
            }
        },
        modifier = Modifier.testTag("thr_rule_${r.id}").then(
            if (state.canEdit) Modifier.clickable(onClickLabel = stringResource(R.string.thr_edit), onClick = onEdit) else Modifier,
        ),
    )
}

/**
 * UI refresh §7: «Всего за день» large on its own card, with today's bar when the child has time today; without a rule
 * an invitation to set one (a parent's phone only). Tapping the figure edits it; the switch saves it as one that blocks
 * everything after the limit (or not), through [onSave].
 */
@Composable
private fun TotalCard(
    rule: ThresholdRule?,
    todayMs: Long?,
    canEdit: Boolean,
    onEdit: (String) -> Unit,
    onSet: () -> Unit,
    onSave: (ThresholdRule) -> Unit,
    onDelete: (ThresholdRule) -> Unit,
) {
    val name = stringResource(R.string.thr_target_total)
    Card(Modifier.fillMaxWidth().testTag("thr_total")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rule == null) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.thr_total_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canEdit) {
                    FilledTonalButton(onSet, Modifier.heightIn(min = 48.dp).testTag("thr_total_set")) { Text(stringResource(R.string.thr_total_set)) }
                }
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().testTag("thr_rule_${rule.id}").then(
                    if (canEdit) Modifier.clickable(onClickLabel = stringResource(R.string.thr_edit)) { onEdit(rule.id) } else Modifier,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(durationText(rule.limitMs), style = MaterialTheme.typography.displaySmall)
                }
                if (canEdit) {
                    IconButton(onClick = { onDelete(rule) }, modifier = Modifier.size(48.dp).testTag("thr_delete_${rule.id}")) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.thr_delete, name))
                    }
                }
            }
            if (todayMs != null) {
                Text(stringResource(R.string.thr_total_today, durationText(todayMs)), style = MaterialTheme.typography.bodyMedium)
                LimitBar(todayMs, rule.limitMs)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.thr_total_block_on_limit), Modifier.weight(1f))
                Switch(
                    checked = rule.blockOnLimit,
                    onCheckedChange = { onSave(rule.copy(blockOnLimit = it)) },
                    enabled = canEdit,
                    modifier = Modifier.testTag("thr_total_block_on_limit"),
                )
            }
        }
    }
}

/** What a rule counts, as the parent reads it: «Total per day», a category's name or the app's label. */
@Composable
internal fun targetName(t: ThresholdTarget, appLabel: (String) -> String): String = when (t) {
    ThresholdTarget.Total -> stringResource(R.string.thr_target_total)
    is ThresholdTarget.Category -> categoryName(t.category)
    is ThresholdTarget.App -> appLabel(t.packageName)
}
