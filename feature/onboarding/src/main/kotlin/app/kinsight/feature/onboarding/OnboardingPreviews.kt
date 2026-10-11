package app.kinsight.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.kinsight.core.designsystem.preview.PreviewTheme
import app.kinsight.core.designsystem.preview.ThemePreviews

@Composable
private fun Step(n: Int, m: Int, content: @Composable () -> Unit) =
    PreviewTheme { CompositionLocalProvider(LocalStepProgress provides StepProgress(n, m), content = content) }

@ThemePreviews
@Composable
internal fun WelcomePreview() = PreviewTheme { WelcomeScreen(onCreate = {}, onJoin = {}) }

@ThemePreviews
@Composable
internal fun NamePreview() = Step(1, 7) { NameScreen("Pixel 8a", invalid = false, onName = {}, onNext = {}, onBack = {}) }

@ThemePreviews
@Composable
internal fun ConsentPreview() = Step(4, 8) { ConsentScreen("Mom", onAgree = {}, onNotNow = {}) }

@ThemePreviews
@Composable
internal fun DisclosurePreview() = Step(2, 7) {
    DisclosureScreen("Nearby devices", "Kinsight finds your family's phones over Bluetooth.", "Agree", onAgree = {}, onNotNow = {})
}

@ThemePreviews
@Composable
internal fun BatteryPreview() = Step(7, 8) { BatteryScreen(BatteryVendor.GOOGLE, unrestricted = false, onOpenSettings = {}, onNext = {}) }

@ThemePreviews
@Composable
internal fun DonePreview() = PreviewTheme { DoneScreen(child = true, admin = false, message = null, onDone = {}) }
