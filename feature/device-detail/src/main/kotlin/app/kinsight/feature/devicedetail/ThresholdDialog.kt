package app.kinsight.feature.devicedetail

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppUsage
import app.kinsight.core.data.RuleMode
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

private const val KIND_TOTAL = "total"
private const val KIND_CATEGORY = "category"
private const val KIND_APP = "app"

/**
 * Adds a rule of child [child] (first «What to do», then the target, then the limit when it is one) or, with
 * [fixedTarget], changes one target's rule, as a full-screen dialog (U15, Material: a form this size is not an alert):
 * close, title and «Save» in the top bar, then one list. The modes offered fit the target: the day total is only
 * limited, a category is limited or blocked, an app also always allowed. A target that already has a rule in [existing]
 * starts from it (its mode, its limit); a new one from a limit of [ThresholdValues.DEFAULT_MS]. One rule per target:
 * saving another mode replaces the old rule. With [fixedMode] the host handles that mode alone: no mode step, and no
 * «block after the limit» box. [onDelete] (with [deleteId] for its tag) adds a «Delete» button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThresholdDialog(
    title: String,
    child: DeviceId,
    fixedTarget: ThresholdTarget?,
    existing: List<ThresholdRule>,
    apps: List<AppUsage>,
    onDismiss: () -> Unit,
    onSave: (ThresholdRule) -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteId: String? = null,
    fixedMode: RuleMode? = null,
) {
    fun ruleOf(t: ThresholdTarget?) = existing.firstOrNull { it.target == t }
    fun limitOf(t: ThresholdTarget?) = ruleOf(t)?.takeIf { it.mode == RuleMode.LIMIT }?.limitMs
    var modeName by rememberSaveable { mutableStateOf((fixedMode ?: ruleOf(fixedTarget)?.mode ?: RuleMode.LIMIT).name) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var pkg by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var blockOnLimit by rememberSaveable { mutableStateOf(ruleOf(fixedTarget)?.blockOnLimit == true) }
    var value by rememberSaveable { mutableStateOf(ThresholdValues.clamp(limitOf(fixedTarget) ?: ThresholdValues.DEFAULT_MS)) }
    val mode = fixedMode ?: RuleMode.valueOf(modeName)
    // An always-allowed rule names an app only; the day total cannot be blocked.
    val shownKind = when {
        mode == RuleMode.ALLOW -> KIND_APP
        mode == RuleMode.BLOCK && kind == KIND_TOTAL -> null
        else -> kind
    }
    val target: ThresholdTarget? = fixedTarget ?: when (shownKind) {
        KIND_TOTAL -> ThresholdTarget.Total
        KIND_CATEGORY -> AppCategory.entries.firstOrNull { it.name == category }?.let(ThresholdTarget::Category)
        KIND_APP -> pkg?.let(ThresholdTarget::App)
        else -> null
    }
    // Picking a target starts from its rule's limit, or the default for a target without one.
    fun picked(t: ThresholdTarget?) {
        value = limitOf(t) ?: ThresholdValues.DEFAULT_MS
        blockOnLimit = ruleOf(t)?.blockOnLimit == true
    }
    val rule: ThresholdRule? = when {
        target == null -> null
        mode == RuleMode.LIMIT ->
            // The day total has its own switch on the screen: an edit here keeps it.
            ThresholdRule.of(child, target, value, if (target == ThresholdTarget.Total) ruleOf(target)?.blockOnLimit == true else blockOnLimit)
        mode == RuleMode.BLOCK -> ThresholdRule.block(child, target)
        else -> (target as? ThresholdTarget.App)?.let { ThresholdRule.allow(child, it.packageName) }
    }
    val modes = RuleMode.entries.filter {
        when (it) {
            RuleMode.LIMIT -> true
            RuleMode.BLOCK -> fixedTarget != ThresholdTarget.Total
            RuleMode.ALLOW -> fixedTarget == null || fixedTarget is ThresholdTarget.App
        }
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
                        TextButton({ rule?.let(onSave) }, enabled = rule != null, modifier = Modifier.testTag("thr_save")) {
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
                if (fixedMode == null) {
                    item(key = "modes") {
                        Column(Modifier.selectableGroup()) {
                            Text(stringResource(R.string.thr_mode_title), style = MaterialTheme.typography.labelLarge)
                            modes.forEach { m ->
                                Choice(stringResource(modeLabel(m)), mode == m, "thr_mode_${m.name.lowercase()}") { modeName = m.name }
                            }
                        }
                    }
                }
                if (mode == RuleMode.ALLOW) {
                    item(key = "allow_note") { Text(stringResource(R.string.thr_allow_note), style = MaterialTheme.typography.bodySmall) }
                }
                if (mode == RuleMode.LIMIT) {
                    item(key = "value") { ValuePicker(value, onChange = { value = it }) }
                    if (fixedMode == null && target != ThresholdTarget.Total) {
                        item(key = "block_on_limit") {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(blockOnLimit, onValueChange = { blockOnLimit = it }, role = Role.Checkbox)
                                    .testTag("thr_block_on_limit"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = blockOnLimit, onCheckedChange = null)
                                Text(stringResource(R.string.thr_block_on_limit), Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
                if (fixedTarget == null) {
                    if (mode != RuleMode.ALLOW) {
                        item(key = "kinds") {
                            Column(Modifier.selectableGroup()) {
                                if (mode == RuleMode.LIMIT) {
                                    Choice(stringResource(R.string.thr_kind_total), kind == KIND_TOTAL, "thr_kind_total") {
                                        kind = KIND_TOTAL
                                        picked(ThresholdTarget.Total)
                                    }
                                }
                                Choice(stringResource(R.string.thr_kind_category), kind == KIND_CATEGORY, "thr_kind_category") { kind = KIND_CATEGORY }
                                Choice(stringResource(R.string.thr_kind_app), kind == KIND_APP, "thr_kind_app") { kind = KIND_APP }
                            }
                        }
                    }
                    when (shownKind) {
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

private fun modeLabel(m: RuleMode): Int = when (m) {
    RuleMode.LIMIT -> R.string.thr_mode_limit
    RuleMode.BLOCK -> R.string.thr_mode_block
    RuleMode.ALLOW -> R.string.thr_mode_allow
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
