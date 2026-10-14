package app.kinsight.core.data

import app.kinsight.core.model.proto.DeviceRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudViewsTest {
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val dad = TestDevice()

    @AfterTest
    fun cleanUp() {
        mom.close()
        kid.close()
        dad.close()
    }

    private suspend fun storage() {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        dad.joinGroupOf(mom, DeviceRole.PARENT, "Dad")
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        dad.learnsStorageFrom(mom)
    }

    private suspend fun at(d: TestDevice, s: CloudState) = CloudViews.status(d.snapshots.snapshots.first(), s, available = true)

    @Test
    fun storageFullWarnsOnlyTheOwner() = runBlocking<Unit> {
        storage()
        val full = CloudState(lastRunMs = 5, lastSyncMs = 4, problem = CloudProblem.STORAGE_FULL)
        val atMom = at(mom, full)
        assertTrue(atMom.on && atMom.selfIsOwner)
        assertEquals(CloudProblem.STORAGE_FULL, atMom.problem)
        val atKid = at(kid, full)
        assertNull(atKid.problem, "the kid cannot free the owner's Disk")
        assertEquals("Mom", atKid.ownerName)
        assertEquals(4L, atKid.lastSyncMs)
        val atDad = at(dad, full)
        assertTrue(atDad.on && !atDad.selfIsOwner)
        assertNull(atDad.problem, "the other parent cannot free the owner's Disk either")
        assertEquals("Mom", atDad.ownerName)
    }

    @Test
    fun aDeadTokenAsksTheOwnerToRestoreAndTheOthersToWait() = runBlocking<Unit> {
        storage()
        assertEquals(CloudProblem.UNAUTHORIZED, at(mom, CloudState(problem = CloudProblem.UNAUTHORIZED)).problem)
        assertEquals(CloudProblem.NO_ACCESS, at(kid, CloudState(problem = CloudProblem.UNAUTHORIZED)).problem)
        assertEquals(CloudProblem.UNAUTHORIZED, at(mom, CloudState(problem = CloudProblem.NO_ACCESS)).problem)
        assertNull(at(kid, CloudState(problem = CloudProblem.NETWORK)).problem, "network trouble is retried quietly")
    }

    @Test
    fun onlyAnAdminParentMayTurnItOn() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom)
        val atMom = at(mom, CloudState())
        assertTrue(atMom.canEnable)
        assertFalse(atMom.on)
        val atKid = at(kid, CloudState())
        assertFalse(atKid.canEnable)
        assertTrue(atKid.isChild)
        assertFalse(CloudViews.status(mom.snapshots.snapshots.first(), CloudState(), available = false).canEnable)
    }
}
