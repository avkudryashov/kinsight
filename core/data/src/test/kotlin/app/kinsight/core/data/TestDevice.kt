package app.kinsight.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kinsight.core.database.KinsightDatabaseFactory
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.RoomUsageQueue
import app.kinsight.core.keystore.AesGcmWrap
import app.kinsight.core.keystore.CloudTokenVault
import app.kinsight.core.keystore.GroupKeyFile
import app.kinsight.core.keystore.RetiredTokenFile
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.Membership
import app.kinsight.core.transport.ble.GroupStore
import app.kinsight.core.transport.ble.TransportStateStore
import app.kinsight.core.usage.AppInfo
import app.kinsight.core.usage.AppInfoSource
import app.kinsight.core.usage.ClockSample
import app.kinsight.core.usage.Collector
import app.kinsight.core.usage.DeviceClock
import app.kinsight.core.usage.EventSource
import app.kinsight.core.usage.LauncherSource
import app.kinsight.core.usage.PublishTarget
import app.kinsight.core.usage.SettingsStore
import app.kinsight.core.usage.UsageAccess
import app.kinsight.core.usage.UsageEvent
import app.kinsight.core.usage.UsagePipeline
import app.kinsight.core.usage.ownUsageAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.Collections
import javax.crypto.KeyGenerator

/** Just enough Context for Room on the JVM: database names are absolute paths. */
class HostContext : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File = File(name)
    override fun getApplicationContext(): Context = this
}

class FakeSyncControl : SyncControl {
    /** Written from the repositories' coroutines (Dispatchers.IO / Default), read by the test thread. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun start(fast: Boolean) { calls += "start:$fast" }
    override fun startByUser() { calls += "startByUser" }
    override fun requestStop() { calls += "requestStop" }
    override suspend fun stopAndWait() { calls += "stop" }
    override suspend fun requestFast(allowed: () -> Boolean): Boolean {
        val ok = allowed()
        calls += if (ok) "requestFast" else "requestFast:refused"
        return ok
    }
}

private object NoEvents : EventSource {
    override fun read(fromMs: Long, untilMs: Long): List<UsageEvent> = emptyList()
}

private object NoLaunchers : LauncherSource {
    override fun launchers(): Set<String> = emptySet()
}

private object NoApps : AppInfoSource {
    override fun info(packageName: String): AppInfo? = null
}

private class FixedClock(private val ms: () -> Long) : DeviceClock {
    override fun sample() = ClockSample(ms(), 1_000_000_000L, 1)
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

/**
 * One phone for core/data tests: its own database, files, settings, pipeline (the app's own-usage rule,
 * [ownUsageAllowed]) and repositories. [sessions] stands in for the sync engine's session counter.
 */
class TestDevice(
    initialKeys: DeviceKeys = DeviceKeys.generate(),
    var now: Long = 1_760_000_000_000L,
    leaveTimeoutMs: Long = 60_000L,
) {
    @Volatile var keys: DeviceKeys = initialKeys // the identity wipe swaps it, like the keystore does
    val dir: File = Files.createTempDirectory("kinsight-device").toFile()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val db = KinsightDatabaseFactory.open(HostContext(), File(dir, "k.db").absolutePath)
    val groups = GroupStore(File(dir, GroupStore.FILE_NAME))
    val transport = TransportStateStore.create(File(dir, "t.preferences_pb"), scope)
    val settings = SettingsStore.create(File(dir, "u.preferences_pb"), scope)
    private val prefsStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "a.preferences_pb") })
    val prefs = AppPrefs(prefsStore)

    /** Leaves [rule] in the old local limits (what a stage 5b app stored; the app itself no longer writes them). */
    suspend fun storeLocalLimit(rule: ThresholdRule) {
        val key = stringPreferencesKey("threshold_rules")
        prefsStore.edit { p ->
            val old = p[key].orEmpty()
            p[key] = (if (old.isEmpty()) "" else "$old\n") + "${rule.child.hex}|${ThresholdRule.keyOf(rule.target)}|${rule.limitMs}"
        }
    }
    val sync = FakeSyncControl()
    val sessions = MutableStateFlow(SessionProgress(finished = 0, syncing = false))
    var identityBroken: Exception? = null
    val keyProvider: suspend () -> DeviceKeys = { identityBroken?.let { throw it } ?: keys }

    /** Usage access (AppOpsManager): a new child grants it only after pairing (UI refresh §6). */
    @Volatile var usageAccessGranted = true
    val pipeline = UsagePipeline(
        db = db,
        collector = Collector(RoomUsageQueue(db), NoEvents, object : UsageAccess {
            override fun granted() = usageAccessGranted
        }, FixedClock { now }, NoLaunchers),
        settings = settings,
        apps = NoApps,
        target = { groups.read()?.let { PublishTarget(it.groupId, keys) } },
        clock = { now },
        ownUsage = { t ->
            t != null &&
                ownUsageAllowed(Membership.compute(t.groupId, RoomEntryStore(db, t.groupId).membershipEntries()), keys.deviceId, settings.current())
        },
    )
    val snapshots = Snapshots(db, groups, keyProvider, transport, scope = scope)
    // A JVM AES key stands in for the Keystore wrap key; one wrap for all the files, like CloudFiles.wrap.
    private val cloudAes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cloudWrap = AesGcmWrap { cloudAes }
    @Volatile var onAccess: () -> Unit = {} // CloudSecrets' onAccess (the app schedules a cloud run there)
    val cloudSecrets = CloudSecrets(
        CloudTokenVault(File(dir, CloudTokenVault.FILE_NAME), cloudWrap),
        GroupKeyFile(File(dir, GroupKeyFile.FILE_NAME), cloudWrap),
        RetiredTokenFile(File(dir, RetiredTokenFile.FILE_NAME), cloudWrap),
        onAccess = { onAccess() },
    )
    val cloudState = CloudStateStore.create(File(dir, "c.preferences_pb"), scope)
    val identityResets: MutableList<Boolean> = Collections.synchronizedList(mutableListOf()) // was the database empty when the identity went
    var identityWipeFails: Exception? = null
    var afterKeyWipeFails: Exception? = null // a crash right after the keys went, before the data
    @Volatile var onKeyWipe: (() -> Unit)? = null // runs (blocking, on io) as the key wipe starts: the marker is set
    val resetMarker = ResetMarker(File(dir, ResetMarker.FILE_NAME))
    val reset = LocalReset(
        db = db,
        pipeline = pipeline,
        forgetGroupFiles = {
            groups.delete()
            transport.clear()
            cloudSecrets.forget()
            cloudState.clear()
        },
        settings = settings,
        prefs = prefs,
        sync = sync,
        wipeIdentity = { onKeyWipe?.invoke(); identityWipeFails?.let { throw it }; identityResets += runBlocking { db.entryDao().all().isEmpty() }; keys = DeviceKeys.generate(); afterKeyWipeFails?.let { throw it } },
        snapshots = snapshots,
        marker = resetMarker,
        runLock = cloudSecrets.runLock,
    )
    val group = GroupRepository(
        snapshots = snapshots,
        db = db,
        groups = groups,
        keys = keyProvider,
        pipeline = pipeline,
        sync = sync,
        scope = scope,
        sessions = sessions,
        forget = { reset.resetIdentity(keepName = true) },
        runLock = cloudSecrets.runLock,
        leaveTimeoutMs = leaveTimeoutMs,
    )
    val appendOwn: OwnAppend = { build -> pipeline.appendOwn(build = build) }
    val usage = UsageRepository(snapshots, prefs, appendOwn)

    fun store() = RoomEntryStore(db, groups.read()!!.groupId)

    fun close() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
        dir.deleteRecursively()
    }
}
