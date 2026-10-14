package app.kinsight.core.data

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.InMemoryDisk
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudRunnerTest {
    private val mom = TestDevice()
    private val kid = TestDevice()
    private val disk = InMemoryDisk()

    @AfterTest
    fun cleanUp() {
        mom.close()
        kid.close()
    }

    private suspend fun family() {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom, DeviceRole.CHILD, "Kid")
    }

    @Test
    fun withoutAStorageOwnerTheCloudIsOff() = runBlocking<Unit> {
        family()
        assertEquals(CloudRun.OFF, mom.runner(disk).run(CloudTrigger.NOW))
        assertFalse(mom.runner(disk).isOn())
        assertTrue(disk.files("app:").isEmpty())
    }

    @Test
    fun aChildsUsageReachesTheParentThroughTheDisk() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        val day = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        assertTrue(day in mom.store().liveEntries())
        assertTrue(mom.runner(disk).isOn())
        assertNotNull(mom.cloudState.current().lastSyncMs)
        assertNull(mom.cloudState.current().problem)
    }

    @Test
    fun aChildWithNothingNewWaitsAnHour() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        val runner = kid.runner(disk)
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.PERIODIC))
        assertEquals(CloudRun.SKIPPED, runner.run(CloudTrigger.PERIODIC))
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW), "opening the app never waits")
        kid.ownSummary("2026-10-13", 1)
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.PERIODIC), "its log moved")
        assertEquals(CloudRun.SKIPPED, runner.run(CloudTrigger.PERIODIC))
        kid.now += CloudRunner.CHILD_IDLE_MS
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.PERIODIC))
    }

    @Test
    fun aParentRunsEveryTime() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        val runner = mom.runner(disk)
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.PERIODIC))
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.PERIODIC))
    }

    @Test
    fun aMemberWithoutAccessWaitsForTheOwner() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.store().storeMembership(mom.store().membershipEntries()) // the owner is known, no token yet
        assertEquals(CloudRun.NO_ACCESS, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.NO_ACCESS, kid.cloudState.current().problem)
    }

    @Test
    fun anOwnerWithoutATokenMustSignInAgain() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        check(mom.pipeline.appendOwn { a -> listOf(a.storageOwnerEntry(mom.keys.deviceId, ByteArray(16) { 1 }, 1)) })
        assertTrue(mom.runner(disk).isOn())
        assertNull(mom.cloudSecrets.tokens())
        assertEquals(setOf(0), mom.cloudSecrets.ring(g).keys().keys)
        assertEquals(CloudRun.UNAUTHORIZED, mom.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.UNAUTHORIZED, mom.cloudState.current().problem)
    }

    @Test
    fun aDeadTokenTellsTheOwnerToRestoreAndTheOthersToWait() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        val dead = tokenDisk(disk) { emptySet() }
        assertEquals(CloudRun.UNAUTHORIZED, mom.runner(dead).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.UNAUTHORIZED, mom.cloudState.current().problem)
        assertEquals(CloudRun.UNAUTHORIZED, kid.runner(dead).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.NO_ACCESS, kid.cloudState.current().problem)
    }

    @Test
    fun aFullDiskIsRecordedAndTheNextGoodRunClearsIt() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        val full = object : DiskApi by disk {
            override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
        }
        assertEquals(CloudRun.STORAGE_FULL, mom.runner(full).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.STORAGE_FULL, mom.cloudState.current().problem)
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        assertNull(mom.cloudState.current().problem)
    }

    @Test
    fun networkTroubleIsRetried() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        val offline = object : DiskApi by disk {
            override suspend fun list(path: String): List<DiskItem> = throw java.io.IOException("no route to host")
        }
        assertEquals(CloudRun.RETRY, mom.runner(offline).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.NETWORK, mom.cloudState.current().problem)
    }

    @Test
    fun describeNeverShowsAToken() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "SECRET-ACCESS", refresh = "SECRET-REFRESH")
        val line = mom.runner(disk).describe()
        assertTrue(line.startsWith("cloud=on") && "self=owner" in line && "access=yes" in line, line)
        assertFalse("SECRET" in line, line)
    }
}
