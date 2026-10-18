package app.kinsight.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.kinsight.core.designsystem.openPrivacyPolicy

@Composable
fun AboutRoute(appVersion: String, onBack: () -> Unit) {
    val context = LocalContext.current
    AboutScreen(
        appVersion,
        onPrivacyPolicy = { openPrivacyPolicy(context) },
        onLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()))
                } catch (_: ActivityNotFoundException) {
                    // No per-app language page on this phone.
                }
            }
        } else {
            null
        },
        onBack = onBack,
    )
}

/**
 * «Ещё → О приложении» (UI refresh §2): the version, the privacy policy, the app's language (Android 13+), and the open
 * source licenses (offline map spec §4: the map data's ODbL attribution, MapLibre, the style, its fonts and icons).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(appVersion: String, onPrivacyPolicy: () -> Unit, onLanguage: (() -> Unit)?, onBack: () -> Unit) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_about)) },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.set_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.set_version, appVersion), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("about_version"))
            TextButton(onPrivacyPolicy, Modifier.testTag("set_privacy")) { Text(stringResource(R.string.set_privacy)) }
            if (onLanguage != null) TextButton(onLanguage, Modifier.testTag("about_language")) { Text(stringResource(R.string.set_language)) }
            TextButton({ licenses = true }, Modifier.testTag("about_licenses")) { Text(stringResource(R.string.about_licenses)) }
        }
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
}

/** One row of the licenses list: a «== Title ==» heading or a paragraph of license text. */
private data class LicenseRow(val heading: Boolean, val text: String)

private fun licenseRows(raw: String): List<LicenseRow> = raw.split("\n\n").map { it.trim('\n') }.filter { it.isNotBlank() }.flatMap { block ->
    // A heading line starts a block; it becomes its own row so it can be styled, the rest follows as text.
    if (block.startsWith("== ")) {
        val head = block.lineSequence().first()
        val rest = block.substring(head.length).trim('\n')
        listOfNotNull(LicenseRow(true, head.trim('=', ' ')), rest.takeIf { it.isNotBlank() }?.let { LicenseRow(false, it) })
    } else {
        listOf(LicenseRow(false, block))
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // ~100 KB: read off the main thread and shown row by row, not as one giant Text.
    val rows by produceState<List<LicenseRow>?>(null) {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.open_source_licenses).use { licenseRows(it.readBytes().decodeToString()) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.about_licenses_close)) } },
        title = { Text(stringResource(R.string.about_licenses)) },
        text = {
            val loaded = rows
            if (loaded == null) {
                CircularProgressIndicator(Modifier.testTag("about_licenses_loading"))
            } else {
                LazyColumn(Modifier.testTag("about_licenses_text")) {
                    items(loaded) { row ->
                        Text(
                            row.text,
                            style = if (row.heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = if (row.heading) 12.dp else 4.dp),
                        )
                    }
                }
            }
        },
    )
}
