package app.kinsight.core.data

import app.kinsight.core.model.parentPinEntry
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.sync.ParentPinRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** What reaches the kid from [from], as a Bluetooth session or the Disk brings it (FROM_PARENTS_TO_ALL). */
    private suspend fun kidTakesPinsOf(from: TestDevice) {
        kid.store().storeMembership(from.store().membershipEntries())
        kid.store().applyBatch(from.store().parentPins())
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
    fun theWaitGrowsAndIsCapped() {
        assertNull(ParentPins.waitAfter(ParentPins.FREE_TRIES - 1))
        assertEquals(ParentPins.FIRST_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES))
        assertEquals(2 * ParentPins.FIRST_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES + 1))
        assertEquals(ParentPins.MAX_WAIT_MS, ParentPins.waitAfter(ParentPins.FREE_TRIES + 40))
        assertTrue(ParentPins.valid("0000") && ParentPins.valid("12345678"))
        assertFalse(ParentPins.valid("123") || ParentPins.valid("123456789") || ParentPins.valid("12 34") || ParentPins.valid("١٢٣٤"))
    }
}
