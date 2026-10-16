package app.kinsight.feature.devicedetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.GeoPoint
import app.kinsight.core.data.MemberView
import app.kinsight.core.data.ZoneDraft
import app.kinsight.core.data.ZoneSave
import app.kinsight.core.location.LocationGraph
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.sync.SafeZoneRules
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The editor's radius stops, within SafeZoneRules' 50..5000 m. */
internal val ZONE_RADII = listOf(50, 100, 150, 200, 300, 500, 750, 1_000, 1_500, 2_000, 3_000, 5_000)
internal const val DEFAULT_RADIUS_M = 150

/** «Моё местоположение» asks for precise location on this tap only, then takes one fix (never in the background). */
@Composable
fun ZonesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = AppGraph.get(context)
    val vm: ZonesViewModel = viewModel { ZonesViewModel(graph.location) }
    val state by vm.state.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<((Pair<Int, Int>?) -> Unit)?>(null) }
    fun fixNow(done: (Pair<Int, Int>?) -> Unit) {
        scope.launch { done(LocationGraph.fixOnce(context)?.let { it.latE7 to it.lonE7 }) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val done = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) fixNow(done) else done(null)
    }
    val s = state ?: run {
        Loading()
        return
    }
    ZonesScreen(
        s, failed,
        onSave = { d, done -> vm.save(d, done) },
        onDelete = vm::delete,
        onHere = { done ->
            if (LocationPermissions.foregroundGranted(context)) {
                fixNow(done)
            } else {
                waiting = done
                permission.launch(LocationPermissions.FOREGROUND)
            }
        },
        onDismissError = vm::dismissError,
        onBack = onBack,
    )
}

/**
 * Spec §8.1: the family's zones; a parent adds, edits and deletes them, a child's phone lists them. [onHere] gives the
 * parent phone's own position (or null).
 */
@Composable
fun ZonesScreen(
    state: ZonesState,
    failed: ZoneSave?,
    onSave: (ZoneDraft, () -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onHere: ((Pair<Int, Int>?) -> Unit) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<ZoneDraft?>(null) }
    val draft = editing
    if (draft != null) {
        ZoneEditor(
            draft, state.children, failed,
            onSave = { d -> onSave(d) { editing = null } },
            onDelete = draft.id?.let { id -> { onDelete(id); editing = null } },
            onHere = onHere,
            onCancel = { editing = null },
        )
        return
    }
    Screen(stringResource(R.string.zones_title), onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("zones_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (failed != null) {
                item(key = "failed") {
                    Column {
                        Text(stringResource(R.string.zone_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onDismissError) { Text(stringResource(R.string.zone_cancel)) }
                    }
                }
            }
            if (!state.canEdit) item(key = "parents_only") { Text(stringResource(R.string.zones_parents_only)) }
            if (state.zones.isEmpty()) item(key = "empty") { Text(stringResource(R.string.zones_empty), Modifier.testTag("zones_empty")) }
            items(state.zones, key = { it.id }) { z ->
                val edit = if (state.canEdit) Modifier.clickable { editing = ZoneDraft(z.id, z.name, z.latE7, z.lonE7, z.radiusM) } else Modifier
                ListItem(
                    headlineContent = { Text(z.name) },
                    supportingContent = { Text(stringResource(R.string.zones_radius, z.radiusM)) },
                    modifier = edit.testTag("zone_${z.id}"),
                )
            }
            if (state.canEdit) {
                item(key = "add") {
                    Button({ editing = ZoneDraft(null, "", 0, 0, DEFAULT_RADIUS_M) }, Modifier.testTag("zones_add")) {
                        Text(stringResource(R.string.zones_add))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ZoneEditor(
    initial: ZoneDraft,
    children: List<Pair<MemberView, GeoPoint>>,
    failed: ZoneSave?,
    onSave: (ZoneDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onHere: ((Pair<Int, Int>?) -> Unit) -> Unit,
    onCancel: () -> Unit,
) = Screen(stringResource(if (initial.id == null) R.string.zone_new_title else R.string.zone_edit_title), onCancel) { padding ->
    var name by remember { mutableStateOf(initial.name) }
    var center by remember { mutableStateOf(if (initial.id != null) initial.latE7 to initial.lonE7 else null) }
    var coords by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(ZONE_RADII.indexOfFirst { it >= initial.radiusM }.takeIf { it >= 0 } ?: ZONE_RADII.lastIndex) }
    var hereFailed by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            name, { name = it }, label = { Text(stringResource(R.string.zone_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("zone_name"),
        )
        Text(
            center?.let { (lat, lon) -> stringResource(R.string.zone_center_value, Coordinates.format(lat, lon)) } ?: stringResource(R.string.zone_center_none),
            modifier = Modifier.testTag("zone_center"),
        )
        children.forEach { (m, p) ->
            OutlinedButton({ center = p.latE7 to p.lonE7 }, Modifier.testTag("zone_from_${m.id.hex}")) { Text(stringResource(R.string.zone_from_child, m.name)) }
        }
        OutlinedButton({
            hereFailed = false
            onHere { got -> if (got != null) center = got else hereFailed = true }
        }, Modifier.testTag("zone_here")) { Text(stringResource(R.string.zone_here)) }
        if (hereFailed) Text(stringResource(R.string.zone_here_failed), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            coords,
            { text ->
                coords = text
                Coordinates.parse(text)?.let { center = it }
            },
            label = { Text(stringResource(R.string.zone_coords)) },
            placeholder = { Text(stringResource(R.string.zone_coords_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("zone_coords"),
        )
        Text(stringResource(R.string.zone_radius_value, ZONE_RADII[radius]))
        Slider(
            radius.toFloat(), { radius = it.roundToInt().coerceIn(0, ZONE_RADII.lastIndex) },
            valueRange = 0f..ZONE_RADII.lastIndex.toFloat(), steps = ZONE_RADII.size - 2,
            modifier = Modifier.testTag("zone_radius"),
        )
        // A refused save keeps the editor open, so the failure shows here too.
        if (failed != null) Text(stringResource(R.string.zone_failed), Modifier.testTag("zone_failed"), color = MaterialTheme.colorScheme.error)
        Button(
            { center?.let { (lat, lon) -> onSave(ZoneDraft(initial.id, name.trim(), lat, lon, ZONE_RADII[radius])) } },
            enabled = SafeZoneRules.nameOk(name.trim()) && center != null,
            modifier = Modifier.testTag("zone_save"),
        ) { Text(stringResource(R.string.zone_save)) }
        if (onDelete != null) TextButton({ askDelete = true }, Modifier.testTag("zone_delete")) { Text(stringResource(R.string.zone_delete)) }
    }
    if (askDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.zone_delete_title, initial.name)) },
            text = { Text(stringResource(R.string.zone_delete_body)) },
            confirmButton = {
                TextButton({
                    askDelete = false
                    onDelete()
                }, Modifier.testTag("zone_delete_confirm")) { Text(stringResource(R.string.zone_delete)) }
            },
            dismissButton = { TextButton({ askDelete = false }) { Text(stringResource(R.string.zone_cancel)) } },
        )
    }
}
