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
import app.kinsight.core.data.MapBlocks
import app.kinsight.core.data.MapWork
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.location.LocationDebug
import app.kinsight.core.location.LocationPermissions
import app.kinsight.core.maps.BlockStore
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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * Debug builds only, guarded by android.permission.DUMP (only adb's shell holds it): reports state and the open invite
 * for the two-phone test (stage 4 pairing creates groups). The answer is the broadcast's result data.
 * No intent filter: adb addresses it explicitly, e.g.
 * `am broadcast -n app.kinsight/.debug.DebugGroupReceiver -a app.kinsight.debug.STATUS`.
 * Cloud hooks: CLOUD_SYNC_NOW (one run of the process-wide runner), CLOUD_STATUS (never a token), CLOUD_FAKE_DISK
 * (`--ez on true|false`, the in-memory Disk instead of Yandex), CLOUD_FAKE_ENABLE (an admin parent with the fake Disk
 * on turns the cloud on through the real path with a fake token, CloudDebugEnable). Location hooks: ZONE_ADD (an admin
 * parent adds a safe zone through LocationRepository; the answer is the save result only), LOCATION_STATUS (the child's
 * gate, tracker mode, outside service, next tick, zone counts, permission and open tamper problems, then the debug
 * switches below; never a coordinate).
 *
 * Device steps of the location service (task-6 report; switches of the debug build only, LocationSeams, off at every
 * process start): LOCATION_REFUSE_FGS (`--es at start|service|none`: the next startForegroundService throws
 * ForegroundServiceStartNotAllowedException, or the service's startForeground throws SecurityException and it stops
 * itself by its start id; none is the real path) and LOCATION_SLOW_TICK (`--ei seconds N`, 0 to 600: every outside tick
 * waits N seconds before its fix, so with N over 30 the broadcast is released before the tick re-arms the alarm; logcat
 * KinsightLocation says "tick held" and "held tick done", the window to kill the process in).
 *
 * Emulator check of the child's location (the in-memory Disk is per process: the two emulators never share a storage,
 * no pack travels between them, and cross-emulator cloud sync is out of scope; the child gets the access over Bluetooth):
 * ```
 * R=app.kinsight/.debug.DebugGroupReceiver; A=app.kinsight.debug
 * # both phones, after pairing over Bluetooth:
 * adb -s <phone> shell am broadcast -n $R -a $A.CLOUD_FAKE_DISK --ez on true
 * # the parent (an admin):
 * adb -s <parent> shell am broadcast -n $R -a $A.CLOUD_FAKE_ENABLE
 * adb -s <parent> shell am broadcast -n $R -a $A.ZONE_ADD --es lat 40.4168 --es lon -3.7038 --ei radius 150 --es name Home
 * adb -s <parent> shell am broadcast -n $R -a $A.SYNC_NOW   # the StorageOwner, CloudAccess and zone reach the child
 * # the child (consented, sharing):
 * adb -s <child> shell am broadcast -n $R -a $A.SYNC_NOW
 * adb -s <child> shell am broadcast -n $R -a $A.CLOUD_STATUS      # cloud=on self=member access=yes
 * adb -s <child> shell am broadcast -n $R -a $A.LOCATION_STATUS
 * ```
 * The fake Disk lives as long as the process: after a restart switch it on again before any cloud run, or the fake token
 * goes to Yandex and the run ends in a 401.
 *
 * Offline map hooks (spec §3; the blocks are not published yet, so the device check takes a locally built one):
 * MAP_INSTALL_LOCAL (`--ei x X --ei y Y --es path P`: the file at P, e.g. pushed to the app's external files dir, is
 * installed as block z10-X-Y through BlockStore.install (partial file, size and sha256, atomic rename), the cell is
 * marked used now, and a map check is queued so the Карта tab sees it; the answer is `installed X Y BYTES`) and
 * MAP_STATUS (the block cells on the phone, the ledger's last-used time of each cell in ms, the catalog version or
 * none, the free bytes). Both answers name cells: adb only, never logcat.
 * ```
 * R=app.kinsight/.debug.DebugGroupReceiver; A=app.kinsight.debug; D=/sdcard/Android/data/app.kinsight/files/maps-in
 * scripts/maps/build-blocks.sh <bbox> <planet YYYYMMDD> <version>   # writes build/maps/<version>/z10-<x>-<y>.pmtiles
 * adb shell mkdir -p $D && adb push build/maps/<version>/z10-510-389.pmtiles $D/
 * adb shell am broadcast -n $R -a $A.MAP_INSTALL_LOCAL --ei x 510 --ei y 389 --es path $D/z10-510-389.pmtiles
 * adb shell am broadcast -n $R -a $A.MAP_STATUS
 * ```
 * A block no child point comes near is deleted after 7 days like any other; a catalog listing the cell with another
 * sha256 replaces it at the next run.
 */
class DebugGroupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val (code, data) = answer(app, intent)
            Log.i(TAG, logLine(intent.action, code, data)) // the data can hold an invite token: never logged
            pending.setResult(code, data, null)
            pending.finish()
        }
    }

    /** The result code (0 done, 1 failed) and data of [intent]; a failure is the answer, never a crash of the receiver. */
    internal suspend fun answer(c: Context, intent: Intent): Pair<Int, String> = try {
        0 to withTimeout(25.seconds) { handle(c, intent) }
    } catch (e: Exception) {
        1 to "error: ${e::class.simpleName}: ${e.message}"
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
        "$PREFIX.CONSENT" -> {
            // «Принимаю» on the consent screen without the screen (a locked test phone): the same call the button makes.
            val repo = AppGraph.get(c).settingsRepository
            repo.agreeToConsent()
            "consent=" + AppGraph.get(c).settings.current().let { "${it.consentAtMs} outdated=${it.consentOutdated} sharing=${it.sharing}" }
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
        "$PREFIX.CLOUD_FAKE_ENABLE" -> DebugCloud.fakeEnable(AppGraph.get(c).cloudSignIn)
        "$PREFIX.ZONE_ADD" -> {
            val d = DebugLocation.draft(intent.getStringExtra("lat"), intent.getStringExtra("lon"), intent.getIntExtra("radius", 0), intent.getStringExtra("name"))
            "zone=" + AppGraph.get(c).location.save(d)
        }
        "$PREFIX.LOCATION_STATUS" -> locationStatus(c) + " " + LocationDebug.seams()
        "$PREFIX.LOCATION_REFUSE_FGS" -> when (val at = intent.getStringExtra("at") ?: "none") {
            "start" -> LocationDebug.refuse(start = true, foreground = false)
            "service" -> LocationDebug.refuse(start = false, foreground = true)
            "none" -> LocationDebug.refuse(start = false, foreground = false)
            else -> error("at must be start, service or none, not $at")
        }
        "$PREFIX.LOCATION_SLOW_TICK" -> LocationDebug.slowTick(intent.getIntExtra("seconds", 0) * 1_000L)
        "$PREFIX.MAP_INSTALL_LOCAL" -> {
            val path = intent.getStringExtra("path") ?: error("path extra missing")
            val answer = DebugMaps.installLocal(mapStore(c), intent.getIntExtra("x", -1), intent.getIntExtra("y", -1), path, System.currentTimeMillis())
            MapWork.checkSoon(c) // the check refreshes the Карта tab's view of the blocks
            answer
        }
        "$PREFIX.MAP_STATUS" -> DebugMaps.status(mapStore(c))
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

    /** The folder AppGraph's store keeps the blocks in (filesDir/maps). */
    private fun mapStore(c: Context) = BlockStore(File(c.filesDir, MapBlocks.DIR))

    /** LOCATION_STATUS: counts, modes and times only (DebugLocation.statusLine); the result never holds a coordinate. */
    private suspend fun locationStatus(c: Context): String {
        val graph = AppGraph.get(c)
        val t = SyncGraph.state(c)
        val problems = DebugLocation.problems(
            t.bluetoothOffSinceMs(), t.notificationHiddenSinceMs(), t.nearbyOffSinceMs(), t.locationAccessOffSinceMs(), t.locationDisabledSinceMs(),
        )
        return DebugLocation.statusLine(
            LocationDebug.status(c),
            graph.familyLocation.watchGate(),
            graph.familyLocation.zones()?.size,
            LocationPermissions.state(c),
            LocationPermissions.systemEnabled(c),
            problems,
            System.currentTimeMillis(),
        )
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
