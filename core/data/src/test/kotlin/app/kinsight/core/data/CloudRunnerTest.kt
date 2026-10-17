package app.kinsight.core.data

import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.Heartbeat
import app.kinsight.core.model.Membership
import app.kinsight.core.model.removeMemberEntry
import app.kinsight.core.model.storageOwnerEntry
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskException
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.usage.UsageSettings
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

    /** Y4 final review Important 1: the run tells how many entries of others it brought, so alerts follow (spec §9). */
    @Test
    fun aRunTellsWhetherItBroughtEntriesOfOthers() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        assertEquals(CloudRun.SYNCED, mom.runner(disk).runCounted(CloudTrigger.NOW).run)
        kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        val runner = mom.runner(disk)
        val first = runner.runCounted(CloudTrigger.NOW)
        assertEquals(CloudRun.SYNCED, first.run)
        assertTrue(first.received > 0)
        assertTrue(CloudWork.alertsNeeded(first))
        val again = runner.runCounted(CloudTrigger.NOW)
        assertEquals(CloudRunOutcome(CloudRun.SYNCED, 0), again)
        assertFalse(CloudWork.alertsNeeded(again))
    }

    /** Re-review: a run that cannot upload (full Disk) still reads the others and tells what it brought. */
    @Test
    fun aFullDiskRunStillTellsWhatItBrought() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW)) // storage.id is on the Disk
        kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        mom.ownSummary("2026-10-13", 1_000) // something of its own to upload, so the full Disk refuses it
        val full = object : DiskApi by disk {
            override suspend fun upload(path: String, bytes: ByteArray) = throw DiskException.StorageFull()
        }
        val outcome = mom.runner(full).runCounted(CloudTrigger.NOW)
        assertEquals(CloudRun.STORAGE_FULL, outcome.run)
        assertTrue(outcome.received > 0)
        assertTrue(CloudWork.alertsNeeded(outcome))
    }

    private suspend fun TestDevice.beat(): app.kinsight.core.model.DecodedEntry {
        var e: app.kinsight.core.model.DecodedEntry? = null
        check(pipeline.appendOwn { a -> listOf(a.create { it.copy(heartbeat = Heartbeat(app_version = "t", protocol_version = 3)) }.also { e = it }) })
        return e!!
    }

    /**
     * Rulings Q14: the owner is heard on this device's clock when its log grows here, by the cloud or a Bluetooth session.
     * An admin parent observes (only it may take the storage over, spec §7.2); a child keeps no others' entries.
     */
    @Test
    fun theOwnerIsHeardWhenItsLogGrowsHere() = runBlocking<Unit> {
        val day = 24L * 60 * 60 * 1000
        val dad = TestDevice()
        try {
            mom.group.createGroup("Mom")
            dad.joinAsAdminOf(mom)
            mom.ownStorage()
            dad.learnsStorageFrom(mom)
            val momId = mom.keys.deviceId
            assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
            val first = dad.now
            assertEquals(CloudRun.SYNCED, dad.runner(disk).run(CloudTrigger.NOW))
            assertEquals(first, dad.cloudState.current().ownerHeardFrom(momId), "first tracked: the week starts now")
            dad.now += day
            assertEquals(CloudRun.SYNCED, dad.runner(disk).run(CloudTrigger.NOW))
            assertEquals(first, dad.cloudState.current().ownerHeardFrom(momId), "nothing new from the owner")
            mom.now += 30 * day // the owner's clock runs far ahead: its stamps decide nothing
            mom.beat()
            assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
            assertEquals(CloudRun.SYNCED, dad.runner(disk).run(CloudTrigger.NOW))
            assertEquals(dad.now, dad.cloudState.current().ownerHeardFrom(momId), "heard by the cloud run")
            dad.now += day
            dad.store().applyBatch(listOf(mom.beat())) // what a Bluetooth session applies
            dad.runner(disk).noteOwnerHeard()
            assertEquals(dad.now, dad.cloudState.current().ownerHeardFrom(momId), "heard after the session")
        } finally {
            dad.close()
        }
    }

    /** Review Minor 5: a removed device is not on, so it never schedules runs that only cancel themselves. */
    @Test
    fun aRemovedDeviceIsNotOn() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        assertTrue(kid.runner(disk).isOn())
        check(mom.pipeline.appendOwn { a -> listOf(a.removeMemberEntry(kid.keys.deviceId, lastSeq = 0)) })
        kid.store().storeMembership(mom.store().membershipEntries())
        assertNotNull(Membership.compute(kid.groups.read()!!.groupId, kid.store().membershipEntries())!!.storageOwner)
        assertFalse(kid.runner(disk).isOn())
        assertEquals(CloudRun.OFF, kid.runner(disk).run(CloudTrigger.NOW))
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

    /** Final review Minor 2: a missing group key is nothing a sign-in fixes, so the owner is not asked to restore access. */
    @Test
    fun aMissingGroupKeyDoesNotAskTheOwnerToSignIn() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        mom.cloudSecrets.ring(mom.groups.read()!!).retain(emptySet())
        assertEquals(CloudRun.NO_ACCESS, mom.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.NETWORK, mom.cloudState.current().problem, "quiet for the owner (CloudViews shows nothing)")
        kid.learnsStorageFrom(mom)
        kid.cloudSecrets.ring(kid.groups.read()!!).retain(emptySet())
        assertEquals(CloudRun.NO_ACCESS, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudProblem.NO_ACCESS, kid.cloudState.current().problem, "a member waits for the owner")
    }

    /** Final review Minor 5: the kept CloudSync reaches the Disk of the current run, so a switched Disk counts at once. */
    @Test
    fun aSwitchedDiskIsUsedFromTheNextRun() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        val other = InMemoryDisk()
        var current: DiskApi = disk
        val runner = mom.runner { _ -> current }
        mom.ownSummary("2026-10-13", 1)
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW))
        assertTrue(disk.files("app:").isNotEmpty())
        current = other
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW))
        assertTrue(other.files("app:").isNotEmpty(), "the second run went to the new Disk")
    }

    @Test
    fun describeNeverShowsAToken() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "SECRET-ACCESS", refresh = "SECRET-REFRESH")
        val line = mom.runner(disk).describe()
        assertTrue(line.startsWith("cloud=on") && "self=owner" in line && "access=yes" in line, line)
        assertFalse("SECRET" in line, line)
    }

    /** Roadmap Y5, privacy policy §13: nothing new goes before the child agreed to the text that names the Disk. */
    @Test
    fun anOldConsentKeepsTheChildOffTheDiskUntilItAgreesAgain() = runBlocking<Unit> {
        family()
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        kid.settings.recordConsent(kid.now, version = UsageSettings.LEGACY_CONSENT_VERSION)
        val day = kid.ownSummary("2026-10-13", 60_000)
        assertEquals(CloudRun.SKIPPED, kid.runner(disk).run(CloudTrigger.NOW))
        assertTrue(disk.files("app:").keys.none { it.contains(kid.keys.deviceId.hex) }, "nothing of the child's on the Disk")
        assertTrue(kid.runner(disk).isOn(), "the worker stays scheduled")
        kid.settings.upgradeConsent()
        assertEquals(kid.now, kid.settings.current().consentAtMs, "the first consent time stays")
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        assertTrue(day in mom.store().liveEntries())
    }

    /** Final review ruling: a child that never agreed uploads nothing at all through the Disk, Heartbeats included. */
    @Test
    fun aChildWithoutConsentPutsNothingOnTheDisk() = runBlocking<Unit> {
        mom.group.createGroup("Mom")
        kid.joinGroupOf(mom, DeviceRole.CHILD, "Kid", consent = false)
        mom.ownStorage()
        kid.learnsStorageFrom(mom)
        kid.beat(storageGeneration = 1)
        assertEquals(CloudRun.SYNCED, mom.runner(disk).run(CloudTrigger.NOW))
        val before = disk.files("app:").mapValues { it.value.toList() }
        assertEquals(CloudRun.SKIPPED, kid.runner(disk).run(CloudTrigger.NOW))
        assertEquals(before, disk.files("app:").mapValues { it.value.toList() }, "the Disk is untouched by the child")
        assertTrue(kid.runner(disk).isOn(), "the worker stays scheduled")
        kid.settings.recordConsent(kid.now)
        assertEquals(CloudRun.SYNCED, kid.runner(disk).run(CloudTrigger.NOW))
        assertTrue(disk.files("app:").keys.any { it.contains(kid.keys.deviceId.hex) }, "after consent the child's folder appears")
    }
}
