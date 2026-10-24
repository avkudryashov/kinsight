package app.kinsight.debug

import android.content.Context
import android.content.Intent
import app.kinsight.core.crypto.Primitives
import app.kinsight.core.data.AppCategory
import app.kinsight.core.data.AppGraph
import app.kinsight.core.data.AppRules
import app.kinsight.core.data.ParentPins
import app.kinsight.core.data.ThresholdRule
import app.kinsight.core.data.ThresholdTarget
import app.kinsight.core.database.RoomEntryStore
import app.kinsight.core.database.wipeAll
import app.kinsight.core.keystore.DeviceIdentity
import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceId
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.addMember
import app.kinsight.core.model.appRuleEntry
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.AppCatalog
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StateEvent
import app.kinsight.core.sync.AppRuleRules
import app.kinsight.core.sync.ParentPinRules
import app.kinsight.core.transport.ble.GroupConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The rules DEMO_FAMILY_CHILD signs as the fake parent, from the broadcast's extras (pure, unit tested). Minutes go up to
 * the next quarter hour and stop at 12 h: AppRuleRules accepts nothing else, so `total_min 2` is a 15 minute limit.
 */
data class DemoRuleSpec(
    val block: List<String> = emptyList(),
    val blockCategories: List<AppCategory> = emptyList(),
    val allow: List<String> = emptyList(),
    val totalMin: Int = 0,
    val limits: Map<String, Int> = emptyMap(),
) {
    /** The rules for [child]; an extra value no rule accepts (not a package, say) is an error, never skipped quietly. */
    fun rules(child: DeviceId): List<ThresholdRule> = buildList {
        block.forEach { add(ThresholdRule.block(child, ThresholdTarget.App(it)) ?: error("block: bad package $it")) }
        blockCategories.forEach { add(checkNotNull(ThresholdRule.block(child, ThresholdTarget.Category(it)))) }
        allow.forEach { add(ThresholdRule.allow(child, it) ?: error("allow: bad package $it")) }
        if (totalMin > 0) add(checkNotNull(ThresholdRule.of(child, ThresholdTarget.Total, limitMs(totalMin), blockOnLimit = true)))
        limits.forEach { (pkg, min) -> add(ThresholdRule.of(child, ThresholdTarget.App(pkg), limitMs(min), blockOnLimit = true) ?: error("limit: bad package $pkg")) }
    }

    companion object {
        val DEFAULT = DemoRuleSpec(block = listOf("com.android.chrome"), allow = listOf("com.google.android.dialer"), totalMin = 15)

        /** Whole quarters of an hour, from 15 min to 12 h. */
        fun limitMs(minutes: Int): Long {
            val ms = minutes.toLong() * 60_000
            val steps = (ms + AppRuleRules.STEP_MS - 1) / AppRuleRules.STEP_MS
            return (steps * AppRuleRules.STEP_MS).coerceIn(AppRuleRules.MIN_MS, AppRuleRules.MAX_MS)
        }

        /** The extras as strings ([totalMin] null when absent); no extra at all is [DEFAULT]. */
        fun parse(block: String?, blockCategory: String?, allow: String?, totalMin: Int?, limit: String?): DemoRuleSpec {
            if (block == null && blockCategory == null && allow == null && totalMin == null && limit == null) return DEFAULT
            require(totalMin == null || totalMin >= 0) { "total_min must not be negative" }
            return DemoRuleSpec(
                block = list(block),
                blockCategories = list(blockCategory).map { name ->
                    AppCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: throw IllegalArgumentException("block_category: unknown $name, one of ${AppCategory.entries.joinToString(",")}")
                },
                allow = list(allow),
                totalMin = totalMin ?: 0,
                limits = list(limit).associate { pair ->
                    val pkg = pair.substringBefore('=', "").trim()
                    val min = pair.substringAfter('=', "").trim().toIntOrNull()
                    require(pkg.isNotEmpty() && min != null && min > 0) { "limit: expected pkg=minutes, not $pair" }
                    pkg to min
                },
            )
        }

        private fun list(v: String?): List<String> = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/**
 * Debug builds only: a fake family without Bluetooth pairing, for emulators. The other side's keys ("Mom" on a child,
 * "Kid" on a parent) live only for the broadcast; their entries are stored as if a sync session had received them.
 */
object DemoFamily {
    const val PIN = "1234"

    /**
     * DEMO_FAMILY_CHILD: this phone becomes the child "Kid" of Mom's group (Mom is the genesis admin parent), with Mom's
     * PIN [PIN] and Mom's rules from the extras; then onboarding is done and [consent] (the CONSENT action) turns sharing on.
     */
    suspend fun child(c: Context, intent: Intent, consent: suspend () -> String): String {
        val graph = AppGraph.get(c)
        check(!withContext(Dispatchers.IO) { graph.groups.exists() }) { "a group already exists" }
        val spec = DemoRuleSpec.parse(
            intent.getStringExtra("block"), intent.getStringExtra("block_category"), intent.getStringExtra("allow"),
            if (intent.hasExtra("total_min")) intent.getIntExtra("total_min", 0) else null, intent.getStringExtra("limit"),
        )
        val self = DeviceIdentity.get(c).keys
        val rules = spec.rules(self.deviceId)
        val momKeys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(momKeys, "Mom")
        val gid = genesis.groupId
        val mom = EntryAuthor(gid, momKeys, lastSeq = genesis.entry.seq, lastLamport = genesis.entry.lamport)
        val salt = Primitives.randomBytes(ParentPinRules.SALT_BYTES)
        val hash = withContext(Dispatchers.Default) { ParentPins.hash(PIN, salt) }
        check(ParentPins.valid(PIN))
        val entries = listOf(genesis.entry, mom.addMember(self.sign.publicKey, self.dh.publicKey, "Kid", DeviceRole.CHILD)) +
            mom.parentPinEntry(salt, hash, ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank = 1) +
            rules.map { mom.ruleEntry(it) }
        graph.pipeline.exclusive {
            graph.db.wipeAll()
            RoomEntryStore(graph.db, gid).applyBatch(entries)
        }
        withContext(Dispatchers.IO) { graph.groups.write(GroupConfig(gid, Primitives.randomBytes(32))) } // as createGroup: last
        graph.prefs.setDeviceName("Kid")
        graph.prefs.setOnboardingDone(true)
        graph.snapshots.invalidate()
        val consented = consent()
        return "child group=${gid.hex} self=${self.deviceId.hex} rules=${rules.size} pin=$PIN $consented"
    }

    /**
     * DEMO_FAMILY_PARENT: this phone creates a group as the admin "Mom" and adds a fake child "Kid", whose 14 days of demo
     * usage, SHARING_RESUMED (and BLOCKING_OFF with `blocking_off=true`) are stored as received; Mom signs three rules.
     */
    suspend fun parent(c: Context, intent: Intent, icon: (Int) -> ByteArray): String {
        val graph = AppGraph.get(c)
        check(!withContext(Dispatchers.IO) { graph.groups.exists() }) { "a group already exists" }
        graph.group.createGroup("Mom")
        val gid = checkNotNull(withContext(Dispatchers.IO) { graph.groups.read() }) { "group not stored" }.groupId
        val kidKeys = DeviceKeys.generate()
        val kidId = kidKeys.deviceId
        check(graph.pipeline.appendOwn { a -> listOf(a.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)) }) { "no group" }

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val days = DemoUsage.generate(now.toLocalDate(), 14, 1L, now.hour, now.minute)
        val kid = EntryAuthor(gid, kidKeys, lastSeq = 0)
        val nowMs = System.currentTimeMillis()
        val kidEntries = buildList {
            add(kid.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.SHARING_RESUMED, from_ms = nowMs, to_ms = nowMs)) })
            DemoUsage.APPS.forEach { app ->
                add(kid.create { it.copy(app_catalog = AppCatalog(app.packageName, app.label, icon(app.color).toByteString(), app.category)) })
            }
            days.forEach { day ->
                add(kid.create { it.copy(usage_summary = day.summary(zone.id)) })
                add(kid.create { it.copy(usage_detail = day.detail(zone.id)) })
            }
            if (intent.getBooleanExtra("blocking_off", false)) {
                add(kid.create { it.copy(state_event = StateEvent(kind = StateEvent.Kind.BLOCKING_OFF, from_ms = nowMs, to_ms = nowMs)) })
            }
        }
        graph.pipeline.exclusive { RoomEntryStore(graph.db, gid).applyBatch(kidEntries) }

        val rules = listOf(
            checkNotNull(ThresholdRule.block(kidId, ThresholdTarget.App(PARENT_BLOCK))),
            checkNotNull(ThresholdRule.of(kidId, ThresholdTarget.App(PARENT_LIMIT), 30 * 60_000L, blockOnLimit = true)),
            checkNotNull(ThresholdRule.allow(kidId, PARENT_ALLOW)),
        )
        check(graph.pipeline.appendOwn { a -> rules.map { a.ruleEntry(it) } }) { "no group" }
        graph.prefs.setDeviceName("Mom")
        graph.prefs.setOnboardingDone(true)
        graph.snapshots.invalidate()
        return "parent group=${gid.hex} kid=${kidId.hex} kidEntries=${kidEntries.size} days=${days.size} rules=${rules.size}"
    }

    /** Demo apps of [DemoUsage.APPS] the parent's three rules name. */
    const val PARENT_BLOCK = "com.roblox.client"
    const val PARENT_LIMIT = "com.google.android.youtube"
    const val PARENT_ALLOW = "com.duolingo"

    private fun EntryAuthor.ruleEntry(rule: ThresholdRule): DecodedEntry {
        val w = AppRules.wire(rule)
        return appRuleEntry(
            childId = w.child_id.toByteArray(), target = w.target, packageName = w.package_name, category = w.category,
            mode = w.mode, limitMs = w.limit_ms, blockOnLimit = w.block_on_limit, rank = 1,
        )
    }
}
