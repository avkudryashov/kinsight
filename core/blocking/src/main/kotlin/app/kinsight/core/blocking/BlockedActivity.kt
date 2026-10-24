package app.kinsight.core.blocking

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.BlockReason
import app.kinsight.core.data.ChildLockStatus
import app.kinsight.core.data.ParentPins
import app.kinsight.core.data.PinCheck
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.designsystem.theme.KinsightTheme
import app.kinsight.core.model.blockOverriddenEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * App blocking spec §5.3: the full-screen block over a closed app. Shows the app (label and icon from this phone's
 * PackageManager), the reason, «Home» (also on Back) and, while the family has a PIN, «Parent: enter PIN». A right PIN
 * opens the app until the end of the local day ([AppPrefs.addPinOverride]) and signs BLOCK_OVERRIDDEN for the parents.
 */
class BlockedActivity : ComponentActivity() {
    private val target = mutableStateOf<Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target.value = targetOf(intent) ?: run {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        val lock = AppGraph.get(this).childLock
        setContent {
            KinsightTheme {
                val status by lock.status.collectAsStateWithLifecycle(ChildLockStatus())
                target.value?.let { BlockScreen(it, status, ::goHome, ::unlock) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetOf(intent)?.let { target.value = it }
    }

    private fun targetOf(intent: Intent): Target? {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return null
        val reason = reasonOf(intent.getStringExtra(EXTRA_REASON_KIND), intent.getStringExtra(EXTRA_REASON_ARG)) ?: BlockReason.Blocked
        @Suppress("DEPRECATION") // the ApplicationInfoFlags overload needs API 33; minSdk is 31
        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = info?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg
        val icon = info?.let { runCatching { bitmapOf(packageManager.getApplicationIcon(it)) }.getOrNull() }
        return Target(pkg, label, icon, reason)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /**
     * [PinCheck.OK]: the override is saved before the screen closes ([PinUnblock]); signing and the sync nudge go on in
     * the app scope. A failed save keeps the screen open: the dialog shows it ([PinUnblock.NotSaved]).
     */
    private suspend fun unlock(pin: String): PinCheck {
        val graph = AppGraph.get(this)
        val pkg = target.value?.packageName ?: return graph.childLock.unlock(pin)
        val check = PinUnblock.unlock(
            graph.scope,
            check = { graph.childLock.unlock(pin) },
            save = { graph.prefs.addPinOverride(pkg, LocalDate.now()) },
            sign = {
                val now = System.currentTimeMillis()
                graph.pipeline.appendOwn { a -> listOf(a.blockOverriddenEntry(pkg, now)) }
                graph.sync.start(fast = true)
            },
            log = { Log.w(TAG, it) },
        )
        if (check == PinCheck.OK) finish()
        return check
    }

    class Target(val packageName: String, val label: String, val icon: ImageBitmap?, val reason: BlockReason)

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REASON_KIND = "reason_kind"
        const val EXTRA_REASON_ARG = "reason_arg"
        private const val TAG = "KinsightBlocking"
        private const val ICON_PX = 192

        fun intent(context: Context, packageName: String, reason: BlockReason): Intent {
            val (kind, arg) = when (reason) {
                BlockReason.Blocked -> "blocked" to null
                is BlockReason.CategoryBlocked -> "category" to reason.category.name
                is BlockReason.LimitPassed -> when (val t = reason.target) {
                    ThresholdTarget.Total -> "limit_total" to null
                    is ThresholdTarget.Category -> "limit_category" to t.category.name
                    is ThresholdTarget.App -> "limit_app" to t.packageName
                }
            }
            return Intent(context, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_REASON_KIND, kind)
                .putExtra(EXTRA_REASON_ARG, arg)
        }

        /** The inverse of [intent]'s reason extras; the limit's size is not carried (the screen does not show it). */
        internal fun reasonOf(kind: String?, arg: String?): BlockReason? {
            val category = arg?.let { a -> AppCategory.entries.firstOrNull { it.name == a } }
            return when (kind) {
                "blocked" -> BlockReason.Blocked
                "category" -> category?.let(BlockReason::CategoryBlocked)
                "limit_total" -> BlockReason.LimitPassed(ThresholdTarget.Total, 0)
                "limit_category" -> category?.let { BlockReason.LimitPassed(ThresholdTarget.Category(it), 0) }
                "limit_app" -> arg?.let { BlockReason.LimitPassed(ThresholdTarget.App(it), 0) }
                else -> null
            }
        }

        private fun bitmapOf(d: android.graphics.drawable.Drawable): ImageBitmap {
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, ICON_PX, ICON_PX)
            d.draw(Canvas(bitmap))
            return bitmap.asImageBitmap()
        }
    }
}

/**
 * The right-PIN path of [BlockedActivity] (review M4): the override is saved first, inside the error handling; a failed
 * save throws [NotSaved] (the screen stays, the dialog says so) and signs nothing. BLOCK_OVERRIDDEN is signed only after
 * the override is saved; a failed signature is logged (the app is open anyway). Runs in [scope] (the app's), so leaving
 * the screen never cuts it short; the caller waits for it.
 */
internal object PinUnblock {
    class NotSaved(cause: Exception) : Exception(cause)

    suspend fun unlock(
        scope: CoroutineScope,
        check: suspend () -> PinCheck,
        save: suspend () -> Unit,
        sign: suspend () -> Unit,
        log: (String) -> Unit = {},
    ): PinCheck {
        val result = check()
        if (result != PinCheck.OK) return result
        var failure: Exception? = null
        scope.launch {
            try {
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                return@launch
            }
            try {
                sign()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("BLOCK_OVERRIDDEN not signed: ${e.javaClass.simpleName}")
            }
        }.join()
        failure?.let {
            log("PIN override not saved: ${it.javaClass.simpleName}")
            throw NotSaved(it)
        }
        return result
    }
}

@Composable
private fun BlockScreen(target: BlockedActivity.Target, status: ChildLockStatus, onHome: () -> Unit, onUnlock: suspend (String) -> PinCheck) {
    var asking by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            target.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(72.dp)) }
            Text(target.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(reasonText(target.reason, target.label), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Button(onHome, Modifier.fillMaxWidth()) { Text(stringResource(R.string.blk_home)) }
            if (status.pinSet) {
                OutlinedButton({ asking = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.blk_parent)) }
                Text(stringResource(R.string.blk_until_tomorrow), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
    if (asking && status.pinSet) PinDialog(status, onUnlock) { asking = false }
}

@Composable
private fun reasonText(reason: BlockReason, label: String): String = when (reason) {
    BlockReason.Blocked -> stringResource(R.string.blk_title_blocked)
    is BlockReason.CategoryBlocked -> stringResource(R.string.blk_title_category, categoryName(reason.category))
    is BlockReason.LimitPassed -> when (val t = reason.target) {
        ThresholdTarget.Total -> stringResource(R.string.blk_title_limit_total)
        is ThresholdTarget.Category -> stringResource(R.string.blk_title_limit, categoryName(t.category))
        is ThresholdTarget.App -> stringResource(R.string.blk_title_limit, label)
    }
}

@Composable
private fun categoryName(c: AppCategory) = stringResource(
    when (c) {
        AppCategory.GAMES -> R.string.blk_cat_games
        AppCategory.SOCIAL -> R.string.blk_cat_social
        AppCategory.VIDEO -> R.string.blk_cat_video
        AppCategory.AUDIO -> R.string.blk_cat_audio
        AppCategory.PHOTOS -> R.string.blk_cat_photos
        AppCategory.NEWS -> R.string.blk_cat_news
        AppCategory.MAPS -> R.string.blk_cat_maps
        AppCategory.PRODUCTIVITY -> R.string.blk_cat_productivity
        AppCategory.OTHER -> R.string.blk_cat_other
    },
)

/** Mirrors the settings' UnlockDialog (feature/settings PinDialogs.kt): digits only, the PIN held in `remember` only. */
@Composable
private fun PinDialog(status: ChildLockStatus, onUnlock: suspend (String) -> PinCheck, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<PinCheck?>(null) }
    var failed by remember { mutableStateOf(false) }
    var notSaved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val waitUntil = status.waitUntilMs
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(ParentPins.LENGTH.last) },
                    label = { Text(stringResource(R.string.blk_pin_title)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                val note = when {
                    waitUntil != null -> stringResource(R.string.blk_pin_wait, DateUtils.formatDateTime(context, waitUntil, DateUtils.FORMAT_SHOW_TIME))
                    notSaved -> stringResource(R.string.blk_pin_not_saved)
                    failed || last == PinCheck.NO_PIN -> stringResource(R.string.blk_pin_failed)
                    last == PinCheck.WRONG -> stringResource(R.string.blk_pin_wrong)
                    else -> null
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                {
                    val typed = pin
                    pin = ""
                    busy = true
                    scope.launch {
                        try {
                            last = onUnlock(typed)
                            failed = false
                            notSaved = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PinUnblock.NotSaved) {
                            notSaved = true
                            failed = false
                        } catch (e: Exception) {
                            failed = true
                            notSaved = false
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && waitUntil == null && ParentPins.valid(pin),
            ) { Text(stringResource(R.string.blk_pin_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.blk_cancel)) } },
    )
}
