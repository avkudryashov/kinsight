package app.kinsight.core.data

import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.grantAdminEntry
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.MemoryPipe
import app.kinsight.core.sync.ParentPinRules
import app.kinsight.core.sync.SecureChannel
import app.kinsight.core.sync.SessionResult
import app.kinsight.core.sync.SyncSession
import app.kinsight.core.transport.ble.SyncPolicies
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChildLockTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    private fun lock(d: TestDevice) = ChildLock(d.snapshots, d.appendOwn, d.prefs, clock = { d.now })

    /** Mom is the admin; Dad is a parent without admin rights; Kid joins last, so it knows both. */
    private suspend fun family() {
        mom.group.createGroup("Mom")
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        kid.joinGroupOf(mom)
    }

    /** One real Bluetooth session between [from] and the kid, as SyncEngine runs it: the parents' PINs ride along (R5). */
    private suspend fun kidTakesPinsOf(from: TestDevice) = coroutineScope {
        suspend fun session(d: TestDevice): SyncSession {
            val m = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())
            return SyncSession(d.store(), SyncPolicies.forDevice(m, d.keys.deviceId, d.settings.current()))
        }
        val (sa, sb) = MemoryPipe.pair()
        val prologue = SecureChannel.prologue(kid.groups.read()!!.groupId)
        val sessionFrom = session(from)
        val sessionKid = session(kid)
        val rf = async { sessionFrom.run(SecureChannel.initiate(sa, from.keys.dh, prologue)) }
        val rk = async { sessionKid.run(SecureChannel.accept(sb, kid.keys.dh, prologue)) }
        assertIs<SessionResult.Completed>(rf.await())
        assertIs<SessionResult.Completed>(rk.await())
    }

    @Test
    fun onlyAnAdminsPinUnlocksAndWrongTriesWait() = runBlocking<Unit> {
        family()
        assertEquals(PinCheck.NO_PIN, lock(kid).unlock("2468"))
        assertEquals(PinSave.NOT_ALLOWED, lock(dad).setPin("1357"), "a parent who is not an admin")
        assertEquals(PinSave.NOT_ALLOWED, lock(kid).setPin("1357"), "a child")
        assertEquals(PinSave.INVALID, lock(mom).setPin("12a4"))
        assertEquals(PinSave.INVALID, lock(mom).setPin("123"))
        assertEquals(PinSave.SAVED, lock(mom).setPin("2468"))
        val entry = mom.store().parentPins().single()
        val p = entry.body.parent_pin!!
        assertEquals(ParentPinRules.SALT_BYTES, p.salt.size)
        assertEquals(ParentPinRules.HASH_BYTES, p.hash.size)
        assertEquals(ParentPins.MEMORY_KIB, p.memory_kib)
        assertFalse(String(entry.bytes, Charsets.ISO_8859_1).contains("2468"), "only the slow hash is signed")

        kidTakesPinsOf(mom)
        assertEquals(PinCheck.OK, lock(kid).unlock("2468"))
        repeat(ParentPins.FREE_TRIES) { assertEquals(PinCheck.WRONG, lock(kid).unlock("0000")) }
        assertEquals(PinCheck.WAIT, lock(kid).unlock("2468"), "the wait holds even for the right PIN")
        kid.now += ParentPins.FIRST_WAIT_MS
        assertEquals(PinCheck.OK, lock(kid).unlock("2468"))
        assertEquals(PinLock(), kid.prefs.pinLock(), "a right PIN clears the count")
    }

    @Test
    fun aNonAdminsPinIsIgnoredAndANewAdminPinReplacesTheOld() = runBlocking<Unit> {
        family()
        val salt = ByteArray(16) { 9 }
        // Signed directly: the app never offers it to Dad, but a receiver must not trust that.
        check(dad.pipeline.appendOwn { a -> listOf(a.parentPinEntry(salt, ParentPins.hash("1357", salt), ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank = 99)) })
        assertEquals(PinSave.SAVED, lock(mom).setPin("2468"))
        kidTakesPinsOf(dad)
        kidTakesPinsOf(mom)
        assertEquals(PinCheck.WRONG, lock(kid).unlock("1357"))
        assertEquals(PinCheck.OK, lock(kid).unlock("2468"))
        assertEquals(PinSave.SAVED, lock(mom).setPin("9753"))
        kidTakesPinsOf(mom)
        assertEquals(PinCheck.WRONG, lock(kid).unlock("2468"))
        assertEquals(PinCheck.OK, lock(kid).unlock("9753"))
    }

    @Test
    fun theStatusSaysWhetherAPinIsSetAndWhoMaySetIt() = runBlocking<Unit> {
        family()
        assertEquals(ChildLockStatus(pinSet = false, canSetPin = true), withTimeout(5_000) { lock(mom).status.first() })
        assertEquals(ChildLockStatus(pinSet = false, canSetPin = false), withTimeout(5_000) { lock(kid).status.first() })
        lock(mom).setPin("2468")
        kidTakesPinsOf(mom)
        assertTrue(withTimeout(5_000) { lock(kid).status.first { it.pinSet } }.pinSet)
    }

    @Test
    fun aWaitDoublesPerWrongTryAndWaitingCountsNoTry() = runBlocking<Unit> {
        family()
        lock(mom).setPin("2468")
        kidTakesPinsOf(mom)
        repeat(ParentPins.FREE_TRIES) { lock(kid).unlock("0000") }
        repeat(3) { assertEquals(PinCheck.WAIT, lock(kid).unlock("0000")) }
        assertEquals(PinLock(ParentPins.FREE_TRIES, kid.now + ParentPins.FIRST_WAIT_MS), kid.prefs.pinLock(), "a WAIT is no try")
        kid.now += ParentPins.FIRST_WAIT_MS
        assertEquals(PinCheck.WRONG, lock(kid).unlock("0000"))
        assertEquals(PinLock(ParentPins.FREE_TRIES + 1, kid.now + 2 * ParentPins.FIRST_WAIT_MS), kid.prefs.pinLock())
    }

    @Test
    fun aWaitWrittenUnderAClockSetAheadIsCutWhenTheClockComesBack() = runBlocking<Unit> {
        family()
        lock(mom).setPin("2468")
        kidTakesPinsOf(mom)
        val year = 365L * 24 * 60 * 60_000
        kid.now += year
        repeat(ParentPins.FREE_TRIES) { assertEquals(PinCheck.WRONG, lock(kid).unlock("0000")) }
        kid.now -= year
        assertEquals(PinCheck.WAIT, lock(kid).unlock("2468"))
        val until = kid.prefs.pinLock().waitUntilMs!!
        assertTrue(until - kid.now <= ParentPins.MAX_WAIT_MS, "persisted at most the longest wait away")
        assertEquals(until, withTimeout(5_000) { lock(kid).status.first() }.waitUntilMs)
        kid.now += ParentPins.MAX_WAIT_MS
        assertEquals(PinCheck.OK, lock(kid).unlock("2468"))
    }

    /**
     * Y5 Task 3 review Minor 1: within one boot the wait runs on the elapsed clock, so a wall clock set ahead does not
     * end it; after a reboot (the elapsed clock restarted) only the wall clock is left.
     */
    @Test
    fun aClockSetAheadDoesNotEndTheWaitWithinOneBoot() = runBlocking<Unit> {
        family()
        lock(mom).setPin("2468")
        kidTakesPinsOf(mom)
        var elapsed = 1_000_000L
        var boot = 7
        val guarded = ChildLock(kid.snapshots, kid.appendOwn, kid.prefs, clock = { kid.now }, elapsed = { ElapsedSample(elapsed, boot) })
        repeat(ParentPins.FREE_TRIES) { assertEquals(PinCheck.WRONG, guarded.unlock("0000")) }
        kid.now += ParentPins.MAX_WAIT_MS + 1 // the wall clock set ahead, no time passed
        assertEquals(PinCheck.WAIT, guarded.unlock("2468"))
        val left = withTimeout(5_000) { guarded.status.first() }.waitUntilMs!! - kid.now
        assertTrue(left in 1..ParentPins.FIRST_WAIT_MS, "what the elapsed clock still owes: $left")
        elapsed += ParentPins.FIRST_WAIT_MS
        assertEquals(PinCheck.OK, guarded.unlock("2468"))
        repeat(ParentPins.FREE_TRIES) { guarded.unlock("0000") }
        boot++ // a reboot: the elapsed clock starts again, the wall clock alone decides
        elapsed = 5_000L
        kid.now += ParentPins.FIRST_WAIT_MS
        assertEquals(PinCheck.OK, guarded.unlock("2468"))
    }

    @Test
    fun theStatusDropsAWaitThatHasPassed() = runBlocking<Unit> {
        family()
        lock(mom).setPin("2468")
        kidTakesPinsOf(mom)
        repeat(ParentPins.FREE_TRIES) { lock(kid).unlock("0000") }
        val until = kid.now + ParentPins.FIRST_WAIT_MS
        assertEquals(until, withTimeout(5_000) { lock(kid).status.first() }.waitUntilMs)
        kid.now = until
        assertNull(withTimeout(5_000) { lock(kid).status.first() }.waitUntilMs)
    }

    @Test
    fun aDepartedAdminsPinCountsUpToItsCut() {
        val momKeys = DeviceKeys.generate()
        val dadKeys = DeviceKeys.generate()
        val genesis = GroupGenesis.create(momKeys, "Mom")
        val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1, lastLamport = 1)
        val dad = EntryAuthor(genesis.groupId, dadKeys, lastSeq = 0, lastLamport = 10)
        val ops = mutableListOf(
            genesis.entry,
            mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT),
            mom.grantAdminEntry(dadKeys.deviceId),
        )
        val salt = ByteArray(ParentPinRules.SALT_BYTES) { 7 }
        val hash = ByteArray(ParentPinRules.HASH_BYTES) { 1 }
        val before = dad.parentPinEntry(salt, hash, ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank = 1)
        ops += dad.leaveGroupEntry() // spec §7.3: the old phone leaves after the move
        val after = dad.parentPinEntry(salt, hash, ParentPins.MEMORY_KIB, ParentPins.ITERATIONS, rank = 2)
        val m = Membership.compute(genesis.groupId, ops)!!
        assertEquals(before.seq, ParentPins.winner(listOf(before, after), m)?.seq, "the PIN signed past the cut never counts")
        assertNull(ParentPins.winner(listOf(after), m))
    }

    @Test
    fun theWaitGrowsAndIsCapped() {
        assertNull(ParentPins.waitAfter(ParentPins.FREE_TRIES - 1))
        assertEquals(ParentPins.FIRST_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES))
        assertEquals(2 * ParentPins.FIRST_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES + 1))
        assertEquals(ParentPins.MAX_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES + 40))
        assertTrue(ParentPins.valid("0000") && ParentPins.valid("12345678"))
        assertFalse(ParentPins.valid("123") || ParentPins.valid("123456789") || ParentPins.valid("12 34") || ParentPins.valid("١٢٣٤"))
    }

    /** Final review Minor 1: a saved PIN asks for an upload at once, so the children get it soon; nothing else does. */
    @Test
    fun aSavedPinAsksForAnUploadSoon() = runBlocking<Unit> {
        family()
        var uploads = 0
        fun counted(d: TestDevice) = ChildLock(d.snapshots, d.appendOwn, d.prefs, clock = { d.now }, onPinSaved = { uploads++ })
        assertEquals(PinSave.INVALID, counted(mom).setPin("123"))
        assertEquals(PinSave.NOT_ALLOWED, counted(dad).setPin("1357"))
        assertEquals(0, uploads)
        assertEquals(PinSave.SAVED, counted(mom).setPin("2468"))
        assertEquals(1, uploads)
    }
}
