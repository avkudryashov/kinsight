package app.kinsight.feature.devicedetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.component.AppIcon
import app.kinsight.core.designsystem.component.dialogTestTags
import app.kinsight.core.designsystem.component.durationText
import app.kinsight.core.model.DeviceId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The daily limits of child [id] (spec §9.7, stage 5b), opened from the child's detail on a parent's phone. */
@Composable
fun ThresholdsRoute(id: DeviceId, onBack: () -> Unit) {
    val graph = AppGraph.get(LocalContext.current)
    val vm: ThresholdsViewModel = viewModel(key = "thresholds-${id.hex}") { ThresholdsViewModel(graph.usage, id, graph.scope, createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pendingDeletes.collectAsStateWithLifecycle()
    state?.let {
        ThresholdsScreen(
            it,
            pending = pending,
            onSave = { t, ms -> vm.save(t, ms) },
            onDelete = vm::delete,
            onUndo = { r -> vm.undo(r) },
            onCommitDelete = { r -> vm.commitDelete(r) },
            onOffered = vm::offered,
            onBack = onBack,
        )
    } ?: Loading()
}

/**
 * The child's rules (target and limit; tap to change the limit, delete with «Undo» in a snackbar) and «Add limit»: the
 * day total, a category or one of the child's apps of the last 30 days (searchable), then the limit in 15-minute steps
 * (−/+ buttons, the value read aloud as it changes). [onDelete] only hides a rule; each id in [pending] (deleted, not
 * yet committed) has its snackbar, also after a recreation: [onCommitDelete] follows when it goes without «Undo»
 * ([onUndo]). A child's phone only reads them ([ThresholdsState.canEdit]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThresholdsScreen(
    state: ThresholdsState,
    pending: Set<String>,
    onSave: (ThresholdTarget, Long) -> Unit,
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
                    if (state.canEdit) stringResource(R.string.thr_intro, state.childName) else stringResource(R.string.thr_parent_only),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.rules.isEmpty()) item(key = "empty") { Text(stringResource(R.string.thr_empty), style = MaterialTheme.typography.titleMedium) }
            items(state.rules, key = { it.id }) { r ->
                val name = targetName(r.target, state::appLabel)
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(durationText(r.limitMs)) },
                    trailingContent = if (state.canEdit) {
                        {
                            IconButton(
                                onClick = { onDelete(r) },
                                modifier = Modifier.size(48.dp).testTag("thr_delete_${r.id}"),
                            ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.thr_delete, name)) }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("thr_rule_${r.id}").then(
                        if (state.canEdit) Modifier.clickable(onClickLabel = stringResource(R.string.thr_edit)) { editing = r.id } else Modifier,
                    ),
                )
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
    if (adding || edited != null) {
        ThresholdDialog(
            title = if (edited != null) targetName(edited.target, state::appLabel) else stringResource(R.string.thr_new),
            fixedTarget = edited?.target,
            existing = state.rules,
            apps = state.apps,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { t, ms ->
                adding = false
                editing = null
                onSave(t, ms)
            },
        )
    }
}

/** What a rule counts, as the parent reads it: «Total per day», a category's name or the app's label. */
@Composable
internal fun targetName(t: ThresholdTarget, appLabel: (String) -> String): String = when (t) {
    ThresholdTarget.Total -> stringResource(R.string.thr_target_total)
    is ThresholdTarget.Category -> categoryName(t.category)
    is ThresholdTarget.App -> appLabel(t.packageName)
}

private const val KIND_TOTAL = "total"
private const val KIND_CATEGORY = "category"
private const val KIND_APP = "app"

/**
 * Adds a rule (pick the target, then the limit) or, with [fixedTarget], changes one target's limit, as a full-screen
 * dialog (U15, Material: a form this size is not an alert): close, title and «Save» in the top bar, then one list
 * with the limit first and the target below it. A target that already has a rule in [existing] starts from its limit;
 * a new one from [ThresholdValues.DEFAULT_MS]. [onDelete] (with [deleteId] for its tag) adds a «Delete» button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThresholdDialog(
    title: String,
    fixedTarget: ThresholdTarget?,
    existing: List<ThresholdRule>,
    apps: List<AppUsage>,
    onDismiss: () -> Unit,
    onSave: (ThresholdTarget, Long) -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteId: String? = null,
) {
    fun limitOf(t: ThresholdTarget?) = existing.firstOrNull { it.target == t }?.limitMs
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var pkg by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf(ThresholdValues.clamp(limitOf(fixedTarget) ?: ThresholdValues.DEFAULT_MS)) }
    val target: ThresholdTarget? = fixedTarget ?: when (kind) {
        KIND_TOTAL -> ThresholdTarget.Total
        KIND_CATEGORY -> AppCategory.entries.firstOrNull { it.name == category }?.let(ThresholdTarget::Category)
        KIND_APP -> pkg?.let(ThresholdTarget::App)
        else -> null
    }
    // Picking a target starts from its rule's limit, or the default for a target without one.
    fun picked(t: ThresholdTarget?) {
        value = limitOf(t) ?: ThresholdValues.DEFAULT_MS
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().dialogTestTags().testTag("thr_dialog"), // its own window: the root flag does not reach it
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onDismiss, Modifier.testTag("thr_cancel")) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.thr_cancel)) }
                    },
                    actions = {
                        TextButton({ target?.let { onSave(it, value) } }, enabled = target != null, modifier = Modifier.testTag("thr_save")) {
                            Text(stringResource(R.string.thr_save))
                        }
                    },
                )
            },
        ) { padding ->
            val q = query.trim()
            val shown = apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("thr_dialog_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "value") { ValuePicker(value, onChange = { value = it }) }
                if (fixedTarget == null) {
                    item(key = "kinds") {
                        Column(Modifier.selectableGroup()) {
                            Choice(stringResource(R.string.thr_kind_total), kind == KIND_TOTAL, "thr_kind_total") {
                                kind = KIND_TOTAL
                                picked(ThresholdTarget.Total)
                            }
                            Choice(stringResource(R.string.thr_kind_category), kind == KIND_CATEGORY, "thr_kind_category") { kind = KIND_CATEGORY }
                            Choice(stringResource(R.string.thr_kind_app), kind == KIND_APP, "thr_kind_app") { kind = KIND_APP }
                        }
                    }
                    when (kind) {
                        KIND_CATEGORY -> items(AppCategory.entries, key = { "cat:${it.name}" }) { c ->
                            Box(Modifier.padding(start = 16.dp)) {
                                Choice(categoryName(c), category == c.name, "thr_category_${c.name}") {
                                    category = c.name
                                    picked(ThresholdTarget.Category(c))
                                }
                            }
                        }
                        KIND_APP -> {
                            item(key = "search") {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    label = { Text(stringResource(R.string.thr_search)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp).testTag("thr_search"),
                                )
                            }
                            if (shown.isEmpty()) item(key = "no_apps") { Text(stringResource(R.string.thr_no_apps), style = MaterialTheme.typography.bodySmall) }
                            // One list, no scroll inside a scroll (U15).
                            items(shown, key = { "app:${it.packageName}" }) { a ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 48.dp)
                                        .selectable(a.packageName == pkg, onClick = {
                                            pkg = a.packageName
                                            picked(ThresholdTarget.App(a.packageName))
                                        }, role = Role.RadioButton)
                                        .testTag("thr_app_${a.packageName}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = a.packageName == pkg, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    AppIcon(a.icon) // its own 40 dp, no padding inside it
                                    Text(a.label, Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                if (onDelete != null) {
                    item(key = "delete") {
                        TextButton(onDelete, Modifier.testTag("thr_delete_${deleteId.orEmpty()}")) { Text(stringResource(R.string.thr_remove)) }
                    }
                }
            }
        }
    }
}

/** Holding a step button repeats its step (U15): the first repeat after [HOLD_DELAY_MS], then every [HOLD_REPEAT_MS]. */
internal const val HOLD_DELAY_MS = 400L
internal const val HOLD_REPEAT_MS = 100L

/** Calls [onDown] on every press and [onRepeat] while the press is held; a plain tap is left to the button's onClick. */
@Composable
private fun Modifier.repeatWhileHeld(enabled: Boolean, onDown: () -> Unit, onRepeat: () -> Unit): Modifier {
    val down by rememberUpdatedState(onDown)
    val repeat by rememberUpdatedState(onRepeat)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down()
                val job = this@coroutineScope.launch {
                    delay(HOLD_DELAY_MS)
                    while (isActive) {
                        repeat()
                        delay(HOLD_REPEAT_MS)
                    }
                }
                waitForUpOrCancellation()
                job.cancel()
            }
        }
    }
}

/**
 * «Notify above»: −/+ (15 minutes each, 48 dp, disabled at the bounds, repeating while held) around the value, read
 * aloud when it changes, and the quick choices of [ThresholdValues.PRESETS_MS]. A release after a hold adds nothing.
 */
@Composable
private fun ValuePicker(value: Long, onChange: (Long) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val current by rememberUpdatedState(value)
    var held by remember { mutableStateOf(false) }
    Text(stringResource(R.string.thr_value_label), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val less = stringResource(R.string.thr_less)
        val more = stringResource(R.string.thr_more)
        FilledTonalIconButton(
            onClick = { if (held) held = false else onChange(ThresholdValues.down(current)) },
            enabled = ThresholdValues.canDown(value),
            modifier = Modifier.size(48.dp)
                .repeatWhileHeld(ThresholdValues.canDown(value), onDown = { held = false }) {
                    held = true
                    onChange(ThresholdValues.down(current))
                }
                .semantics { contentDescription = less }.testTag("thr_minus"),
        ) { Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {}) }
        Text(
            durationText(value),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 96.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("thr_value"),
        )
        FilledTonalIconButton(
            onClick = { if (held) held = false else onChange(ThresholdValues.up(current)) },
            enabled = ThresholdValues.canUp(value),
            modifier = Modifier.size(48.dp)
                .repeatWhileHeld(ThresholdValues.canUp(value), onDown = { held = false }) {
                    held = true
                    onChange(ThresholdValues.up(current))
                }
                .semantics { contentDescription = more }.testTag("thr_plus"),
        ) { Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {}) }
    }
    Text(stringResource(R.string.thr_presets), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThresholdValues.PRESETS_MS.forEach { ms ->
            FilterChip(
                selected = value == ms,
                onClick = { onChange(ms) },
                label = { Text(durationText(ms)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("thr_preset_${ms / 60_000}"),
            )
        }
    }
    Text(stringResource(R.string.thr_value_range), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Choice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick, role = Role.RadioButton).testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
) {
    RadioButton(selected = selected, onClick = null)
    Text(text, Modifier.padding(start = 12.dp))
}
