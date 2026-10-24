package app.kinsight.core.blocking

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.kinsight.core.data.AppGraph
import app.kinsight.core.usage.AppOpsUsageAccess
import app.kinsight.core.usage.HomeLauncherSource
import app.kinsight.core.usage.UsageStatsEventSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * App blocking spec §5.2: on every window change asks [Enforcer]; on a Block goes Home and shows [BlockedActivity].
 * Wiring only. The rules, categories, PIN overrides and today's usage are cached in StateFlows on the service's own scope,
 * so [onAccessibilityEvent] never waits; today's usage is read off the main thread at most every
 * [Enforcer.USAGE_REFRESH_MS], and the minute tick re-checks the app on screen while the phone is awake and unlocked.
 */
class BlockingAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var enforcer: Enforcer? = null
    private var refresh: (() -> Unit)? = null
    private var recheck: Job? = null

    override fun onServiceConnected() {
        scope?.cancel() // a reconnect starts over
        val graph = AppGraph.get(this)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val inputs: StateFlow<EnforcerInputs> = combine(graph.snapshots.snapshots, graph.settings.settings, EnforcerInputs::of)
            .stateIn(s, SharingStarted.Eagerly, EnforcerInputs.OFF)
        val pins = graph.prefs.pinOverrides.stateIn(s, SharingStarted.Eagerly, emptyMap())
        val today = MutableStateFlow<DatedUsage?>(null)
        val reader = TodayUsageReader(
            UsageStatsEventSource(getSystemService(UsageStatsManager::class.java)), AppOpsUsageAccess(this),
            HomeLauncherSource(packageManager), ZoneId::systemDefault, categoryOf = { inputs.value.categoryOf(it) },
        )
        suspend fun readToday(nowMs: Long): DatedUsage? = withContext(Dispatchers.Default) {
            try {
                reader.read(nowMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "today's usage failed: ${e.javaClass.simpleName}")
                null
            }
        }
        val allowList = AndroidSystemAllowList(this)
        val e = Enforcer(
            rules = { inputs.value.rules },
            categoryOf = { inputs.value.categoryOf(it) },
            today = { today.value },
            date = LocalDate::now,
            pinAllowed = { LocalDate.now().let { d -> pins.value.filterValues { it == d }.keys } },
            system = allowList,
            isHome = allowList::isHome,
            isOverlay = allowList::isOverlay,
            ownPackage = packageName,
            enabled = { inputs.value.enabled },
            clock = System::currentTimeMillis,
            inCall = allowList::inCall,
        ).also { enforcer = it }
        // Usage is read only when it can change a decision: a blocking LIMIT exists, the phone is awake and either an app
        // that is not allow-listed is on screen (Enforcer.current) or the cached usage is of another day (C1: a Block of
        // yesterday cleared current; the stale value counts as unknown until this read replaces it).
        fun wantsUsage() = inputs.value.needsUsage && (e.current != null || e.usageStale()) && awake()
        // Main thread only: a window change starts a read when the last one is older than USAGE_REFRESH_MS.
        // 0, not Long.MIN_VALUE: now - MIN_VALUE overflows to a negative age and the first read would never start.
        var readAtMs = 0L
        var reading: Job? = null
        refresh = {
            val now = System.currentTimeMillis()
            if (wantsUsage() && reading?.isActive != true && now - readAtMs >= Enforcer.USAGE_REFRESH_MS) {
                readAtMs = now
                // The app that triggered this read was decided on the cached usage (empty after a service start, up to
                // USAGE_REFRESH_MS old otherwise): decide it again on the fresh value instead of waiting for the tick.
                reading = s.launch {
                    today.value = readToday(now)
                    act(e.onTick())
                }
            }
        }
        // The tick reads today's usage first, so a limit closes the app on screen within a minute of running out.
        // Never on a locked or dark phone: nothing is in front of the child then.
        s.launch {
            while (isActive) {
                delay(Enforcer.TICK_MS)
                if (!awake()) continue
                if (wantsUsage()) {
                    val now = System.currentTimeMillis()
                    readAtMs = now
                    today.value = readToday(now)
                }
                act(e.onTick())
            }
        }
        // New inputs (the rules loaded after a service start, a rule a parent just added, a consent): the app on screen was
        // decided without them, so decide it again now, and read today's usage if a limit now needs it.
        s.launch {
            inputs.drop(1).collect {
                refresh?.invoke()
                act(e.onTick())
            }
        }
        s.launch(Dispatchers.Default) { graph.prefs.prunePinOverrides(LocalDate.now()) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Kinsight's own windows, the block screen included, go through too: the Enforcer clears the app behind them.
        enforcer?.let { e ->
            act(e.onForeground(pkg))
            recheck?.cancel()
            recheck = if (e.hasPendingRecheck) {
                // A window of the app just blocked: if no newer window follows (the block screen's own would), the app
                // opened something over the block screen and is in front again.
                scope?.launch {
                    delay(Enforcer.DEBOUNCE_MS + 50)
                    act(e.onDebounceElapsed())
                }
            } else {
                null
            }
        }
        refresh?.invoke()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        enforcer = null
        refresh = null
        recheck = null
        super.onDestroy()
    }

    private fun awake(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    /**
     * The block screen goes over the blocked app; its Home and Back lead home, a PIN unblock finishes it back into the app.
     * No GLOBAL_ACTION_HOME first: the launcher's transition lands after the start and covers the block screen (emulator,
     * Android 15).
     */
    private fun act(action: Enforcer.Action) {
        if (action !is Enforcer.Action.Block) return
        startActivity(BlockedActivity.intent(this, action.packageName, action.reason))
    }

    private companion object {
        const val TAG = "KinsightBlocking"
    }
}
