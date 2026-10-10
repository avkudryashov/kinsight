package app.kinsight.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.crypto.toHex
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.proto.AddMember
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.proto.MembershipOp
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.PROTOCOL_VERSION
import app.kinsight.core.transport.ble.GroupConfig
import app.kinsight.core.transport.ble.SyncGraph
import app.kinsight.core.transport.ble.SyncService
import app.kinsight.core.usage.PublishTarget
import app.kinsight.core.usage.UsageGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration.Companion.seconds

/**
 * Debug builds only, guarded by android.permission.DUMP (only adb's shell holds it): seeds a test group on two phones
 * before stage 4 pairing exists, and reports state for the device test. The answer is the broadcast's result data.
 * No intent filter: adb addresses it explicitly, e.g.
 * `am broadcast -n app.kinsight/.debug.DebugGroupReceiver -a app.kinsight.debug.STATUS`.
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
            Log.i(TAG, logLine(intent.action, code, data)) // the data can hold the discovery key (seeds): never logged
            pending.setResult(code, data, null)
            pending.finish()
        }
    }

    private suspend fun handle(c: Context, intent: Intent): String = when (intent.action) {
        "$PREFIX.IDENTITY" -> {
            val k = DeviceIdentity.get(c).keys
            "${k.sign.publicKey.toHex()}:${k.dh.publicKey.toHex()}:${k.deviceId.hex}"
        }
        "$PREFIX.CREATE_GROUP" -> createGroup(c, intent.getStringExtra("name") ?: Build.MODEL, intent.getStringArrayExtra("members").orEmpty().filterNotNull())
        "$PREFIX.JOIN_GROUP" -> joinGroup(c, DebugSeed.decode(intent.getStringExtra("seed") ?: error("seed missing")))
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
        "$PREFIX.SYNC_NOW" -> SyncService.instance?.syncNow() ?: "service not running"
        "$PREFIX.STATUS" -> status(c)
        else -> error("unknown action ${intent.action}")
    }

    private suspend fun createGroup(c: Context, name: String, members: List<String>): String {
        val groups = SyncGraph.groups(c)
        check(!groups.exists()) { "a group already exists; clear app data to start over" }
        val keys = DeviceIdentity.get(c).keys
        val genesis = GroupGenesis.create(keys, name)
        val db = KinsightDatabaseFactory.shared(c)
        check(RoomEntryStore(db, genesis.groupId).liveEntries().isEmpty()) { "the database already holds entries; clear app data" }
        val adds = members.map(MemberSpec::parse)
        // Same lock and appendOwn as usage; the target is explicit because the group file does not exist yet.
        UsageGraph.pipeline(c).appendOwn(PublishTarget(genesis.groupId, keys)) { author ->
            check(author.lastSeq == 0L) { "own log not empty" }
            val creator = EntryAuthor(genesis.groupId, keys, lastSeq = 1, lastLamport = genesis.entry.lamport)
            listOf(genesis.entry) + adds.map { m ->
                creator.create { it.copy(membership_op = MembershipOp(add = AddMember(m.sign.toByteString(), m.dh.toByteString(), m.name))) }
            }
        }
        val config = GroupConfig(genesis.groupId, Primitives.randomBytes(32))
        groups.write(config) // last: a crash before this leaves no group, and the check above refuses a second genesis
        val membership = RoomEntryStore(db, genesis.groupId).membershipEntries().map { it.bytes }
        return DebugSeed(config, membership).encode()
    }

    private suspend fun joinGroup(c: Context, seed: DebugSeed): String {
        val groups = SyncGraph.groups(c)
        check(!groups.exists()) { "a group already exists; clear app data to start over" }
        val self = DeviceIdentity.get(c).keys.deviceId
        val entries = seed.membership.map(DecodedEntry::decode)
        val view = Membership.compute(seed.config.groupId, entries) ?: error("the seed has no valid genesis")
        check(self in view.active) { "this device is not a member of the seeded group" }
        val store = RoomEntryStore(KinsightDatabaseFactory.shared(c), seed.config.groupId)
        check(store.liveEntries().isEmpty()) { "the database already holds entries; clear app data" }
        store.applyBatch(entries)
        groups.write(seed.config)
        return "joined ${seed.config.groupId.hex}"
    }

    private suspend fun status(c: Context): String {
        val service = SyncGraph.snapshot.value.state
        val group = SyncGraph.groups(c).read() ?: return "group=none service=$service"
        val self = DeviceIdentity.get(c).keys.deviceId
        val store = RoomEntryStore(KinsightDatabaseFactory.shared(c), group.groupId)
        val vector = store.versionVector().entries.toSortedMap().entries.joinToString(",") { "${it.key.hex}:${it.value}" }
        val btOff = store.entriesAfter(self, 0, Int.MAX_VALUE).count { it.body.state_event?.kind == StateEvent.Kind.BLUETOOTH_OFF }
        return "group=${group.groupId.hex} self=${self.hex} vector=$vector btOff=$btOff service=$service"
    }

    companion object {
        private const val TAG = "KinsightDebug"
        private const val PREFIX = "app.kinsight.debug"

        /** A short status only: the result data (seeds carry the discovery key) goes to adb, never to logcat. */
        internal fun logLine(action: String?, code: Int, data: String): String = "$action: code=$code, ${data.length} chars"
    }
}
