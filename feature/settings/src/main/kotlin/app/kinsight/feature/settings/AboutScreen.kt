package app.kinsight.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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

/** «Ещё → О приложении» (UI refresh §2): the version, the privacy policy, the app's language (Android 13+). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(appVersion: String, onPrivacyPolicy: () -> Unit, onLanguage: (() -> Unit)?, onBack: () -> Unit) = Scaffold(
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
    }
}
