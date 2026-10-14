package app.kinsight.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import app.kinsight.core.crypto.toHex
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.CloudTrigger
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * Debug builds only, guarded by android.permission.DUMP (only adb's shell holds it): reports state and the open invite
 * for the two-phone test (stage 4 pairing creates groups). The answer is the broadcast's result data.
 * No intent filter: adb addresses it explicitly, e.g.
 * `am broadcast -n app.kinsight/.debug.DebugGroupReceiver -a app.kinsight.debug.STATUS`.
 * Cloud hooks: CLOUD_SYNC_NOW (one run of the process-wide runner), CLOUD_STATUS (never a token), CLOUD_FAKE_DISK
 * (`--ez on true|false`, the in-memory Disk instead of Yandex).
 */
class DebugGroupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val (code, data) = try {
                0 to withTimeout(25.seconds) { handle(app, intent) }
            } catch (e: Exception) {
                1 to "error: ${e::class.simpleName}: ${e.message}"
            }
            Log.i(TAG, logLine(intent.action, code, data)) // the data can hold an invite token: never logged
            pending.setResult(code, data, null)
            pending.finish()
        }
    }

    private suspend fun handle(c: Context, intent: Intent): String = when (intent.action) {
        "$PREFIX.IDENTITY" -> {
            val k = DeviceIdentity.get(c).keys
            "${k.sign.publicKey.toHex()}:${k.dh.publicKey.toHex()}:${k.deviceId.hex}"
        }
        "$PREFIX.INVITE_URI" -> AppGraph.get(c).pairing.currentInviteUri.value ?: "none"
        "$PREFIX.SHARING" -> {
            val on = intent.getBooleanExtra("on", true)
            UsageGraph.pipeline(c).setSharing(on)
            "sharing=$on"
        }
        "$PREFIX.BUMP" -> {
            // A fresh publish plus a new Heartbeat (it supersedes the last one): our vector moves for sure.
            val p = UsageGraph.pipeline(c)
            val report = p.collectAndPublish(force = true)
            val beat = p.appendOwn { author ->
                listOf(author.create { it.copy(heartbeat = Heartbeat(app_version = "bump-${System.currentTimeMillis()}", protocol_version = PROTOCOL_VERSION)) })
            }
            "published=${report.publish} heartbeat=$beat"
        }
        "$PREFIX.DEFAULT_NAME" -> {
            // The onboarding name field starts from the stored device name (screenshots: adb cannot type Cyrillic).
            val name = intent.getStringExtra("name") ?: error("name extra missing")
            AppGraph.get(c).prefs.setDeviceName(name)
            "name=${AppGraph.get(c).prefs.current().deviceName}"
        }
        "$PREFIX.DEMO_USAGE" -> demoUsage(c, intent.getIntExtra("days", 14), intent.getLongExtra("seed", 1L))
        "$PREFIX.SYNC_NOW" -> SyncService.instance?.syncNow() ?: "service not running"
        "$PREFIX.STATUS" -> status(c)
        "$PREFIX.CLOUD_SYNC_NOW" -> "cloud=" + AppGraph.get(c).cloud.run(CloudTrigger.NOW)
        "$PREFIX.CLOUD_STATUS" -> AppGraph.get(c).cloud.describe()
        "$PREFIX.CLOUD_FAKE_DISK" -> DebugCloud.fakeDisk(intent.getBooleanExtra("on", true))
        else -> error("unknown action ${intent.action}")
    }

    /**
     * Store screenshots: signs a generated history ([DemoUsage]) as this child's own UsageSummary/UsageDetail per date
     * (same supersede keys as the Publisher), plus an AppCatalog per demo app; today is signed last, so it is the
     * newest summary. The real collector's next publish of a changed day supersedes the demo day again.
     */
    private suspend fun demoUsage(c: Context, days: Int, seed: Long): String {
        val group = SyncGraph.groups(c).read() ?: error("no group")
        val self = DeviceIdentity.get(c).keys.deviceId
        val store = RoomEntryStore(KinsightDatabaseFactory.shared(c), group.groupId)
        val role = Membership.compute(group.groupId, store.membershipEntries())?.roleOf(self)
        check(role == DeviceRole.CHILD) { "DEMO_USAGE needs a CHILD device, this one is $role" }
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val generated = DemoUsage.generate(now.toLocalDate(), days, seed, now.hour, now.minute)
        val icons = DemoUsage.APPS.associate { it.packageName to demoIcon(it.color) }
        val ok = UsageGraph.pipeline(c).appendOwn { author ->
            DemoUsage.APPS.map { app ->
                author.create {
                    it.copy(app_catalog = AppCatalog(app.packageName, app.label, icons.getValue(app.packageName).toByteString(), app.category))
                }
            } + generated.flatMap { day ->
                listOf(
                    author.create { it.copy(usage_summary = day.summary(zone.id)) },
                    author.create { it.copy(usage_detail = day.detail(zone.id)) },
                )
            }
        }
        check(ok) { "no publish target" }
        return generated.joinToString(" ", prefix = "demo days=${generated.size} ") { "${it.date}=${it.totalMs / 60_000}m/${it.unlocks}u" }
    }

    private fun demoIcon(color: Int): ByteArray {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            Canvas(bitmap).drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 11f, 11f, paint)
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "WebP encoding failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun status(c: Context): String {
        val service = SyncGraph.snapshot.value.state
        val group = SyncGraph.groups(c).read() ?: return "group=none service=$service"
        val self = DeviceIdentity.get(c).keys.deviceId
        val store = RoomEntryStore(KinsightDatabaseFactory.shared(c), group.groupId)
        val role = Membership.compute(group.groupId, store.membershipEntries())?.roleOf(self)
        val vector = store.versionVector().entries.toSortedMap().entries.joinToString(",") { "${it.key.hex}:${it.value}" }
        val own = store.entriesAfter(self, 0, Int.MAX_VALUE)
        val btOff = own.count { it.body.state_event?.kind == StateEvent.Kind.BLUETOOTH_OFF }
        val usageKinds = setOf(EntryKind.USAGE_SUMMARY, EntryKind.USAGE_DETAIL, EntryKind.APP_CATALOG)
        val usageSeq = own.filter { it.kind in usageKinds }.maxOfOrNull { it.seq } ?: 0L
        val hidden = SyncGraph.state(c).notificationHiddenSinceMs() != null
        val hiddenEvents = store.stateEvents().count { it.body.state_event?.kind == StateEvent.Kind.NOTIFICATION_HIDDEN }
        return "group=${group.groupId.hex} self=${self.hex} vector=$vector btOff=$btOff role=$role service=$service " +
            "usageSeq=$usageSeq hidden=$hidden hiddenEvents=$hiddenEvents"
    }

    companion object {
        private const val TAG = "KinsightDebug"
        private const val PREFIX = "app.kinsight.debug"

        /** A short status only: the result data (an invite carries its token) goes to adb, never to logcat. */
        internal fun logLine(action: String?, code: Int, data: String): String = "$action: code=$code, ${data.length} chars"
    }
}
