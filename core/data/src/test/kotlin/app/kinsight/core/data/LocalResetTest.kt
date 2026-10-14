package app.kinsight.core.data

import app.kinsight.core.keystore.CloudTokenVault
import app.kinsight.core.keystore.GroupKeyFile
import app.kinsight.core.transport.cloud.DiskApi
import app.kinsight.core.transport.cloud.DiskItem
import app.kinsight.core.transport.cloud.InMemoryDisk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalResetTest {
    private val d = TestDevice()

    @AfterTest
    fun cleanUp() = d.close()

    @Test
    fun forgetGroupLeavesNoTraceButTheName() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.prefs.setOnboardingDone(true)
        d.group.createGroup("Pixel")
        d.settings.recordConsent(1L)
        d.transport.setStoppedByUser(true)
        d.reset.forgetGroup()
        assertFalse(d.groups.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertNull(d.settings.current().consentAtMs)
        assertFalse(d.transport.stoppedByUser())
        assertEquals(Prefs(deviceName = "Pixel"), d.prefs.current())
        assertEquals("stop", d.sync.calls[1]) // after createGroup's start: the service stops before the wipe
        assertTrue(d.identityResets.isEmpty())
    }

    @Test
    fun resetIdentityForgetsTheKeysBeforeTheData() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.group.createGroup("Pixel")
        d.reset.resetIdentity()
        // Keys first: a crash after them leaves a new identity, never the old keys over an emptied log (audit A-4).
        assertEquals(listOf(false), d.identityResets)
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(Prefs(), d.prefs.current())
        assertFalse(d.groups.exists())
        assertFalse(d.resetMarker.exists())
    }

    @Test
    fun anIdentityResetAfterLeavingKeepsOnlyTheName() = runBlocking<Unit> {
        d.prefs.setDeviceName("Pixel")
        d.prefs.setOnboardingDone(true)
        val old = d.keys.deviceId
        d.group.createGroup("Pixel")
        d.reset.resetIdentity(keepName = true)
        assertEquals(listOf(false), d.identityResets)
        assertTrue(old != d.keys.deviceId)
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(Prefs(deviceName = "Pixel"), d.prefs.current())
        assertFalse(d.groups.exists())
        assertFalse(d.resetMarker.exists())
    }

    @Test
    fun anInterruptedResetIsFinishedAtTheNextStart() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.identityWipeFails = IllegalStateException("killed")
        assertFailsWith<IllegalStateException> { d.reset.resetIdentity() }
        assertTrue(d.resetMarker.exists())
        assertFalse(d.groups.exists())
        assertTrue(d.db.entryDao().all().isNotEmpty()) // the keys go first: the data is still there
        assertEquals(ResetStatus.Failed(ResetKind.NEW_IDENTITY, "killed"), d.reset.status.value)
        d.reset.clearFailure()
        assertIs<ResetStatus.Failed>(d.reset.status.value) // no dismissing while the marker is pending
        d.identityWipeFails = null
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(1, d.identityResets.size)
        assertFalse(d.reset.finishInterruptedReset()) // nothing pending any more
    }

    @Test
    fun aCrashAfterTheKeyWipeIsFinishedAtTheNextStart() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.prefs.setDeviceName("Pixel")
        d.afterKeyWipeFails = IllegalStateException("killed")
        assertFailsWith<IllegalStateException> { d.reset.resetIdentity() }
        assertEquals(listOf(false), d.identityResets) // keys gone, data still there
        assertTrue(d.resetMarker.exists())
        d.afterKeyWipeFails = null
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertEquals(2, d.identityResets.size)
        assertEquals(Prefs(), d.prefs.current())
    }

    @Test
    fun aFinishedResetWithTheMarkerLeftBehindIsRunAgainHarmlessly() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.reset.resetIdentity()
        d.resetMarker.set()
        assertTrue(d.reset.finishInterruptedReset())
        assertFalse(d.resetMarker.exists())
        assertTrue(d.db.entryDao().all().isEmpty())
        assertFalse(d.groups.exists())
        assertEquals(Prefs(), d.prefs.current())
        assertEquals(ResetStatus.Idle, d.reset.status.value)
    }

    @Test
    fun forgettingTheGroupWipesTokensAndKeys() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.ownStorage()
        d.cloudState.record(5, synced = true, problem = null)
        d.reset.forgetGroup()
        assertNull(d.cloudSecrets.tokens())
        assertFalse(File(d.dir, CloudTokenVault.FILE_NAME).exists())
        assertFalse(File(d.dir, GroupKeyFile.FILE_NAME).exists())
        assertEquals(CloudState(), d.cloudState.current())
        d.group.createGroup("Pixel")
        val g = d.groups.read()!!
        val ring = d.cloudSecrets.ring(g).keys()
        assertEquals(setOf(0), ring.keys, "the next group starts from its own generation 0")
        assertContentEquals(g.discoveryKey, ring.getValue(0))
    }

    @Test
    fun anIdentityResetWipesTheTokensToo() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.ownStorage()
        d.reset.resetIdentity()
        assertNull(d.cloudSecrets.tokens())
        assertFalse(File(d.dir, GroupKeyFile.FILE_NAME).exists())
    }

    @Test
    fun theWipeWaitsForACloudRunThatWouldWriteTheOldKeysBack() = runBlocking<Unit> {
        d.group.createGroup("Pixel")
        d.ownStorage()
        // Written by the first run, so the runs under test find the storage and talk to the Disk.
        val disk = InMemoryDisk()
        assertEquals(CloudRun.SYNCED, d.runner(disk).run(CloudTrigger.NOW))
        val g = d.groups.read()!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Each run stores a group key of the old group from inside CloudSync (as absorbing a rotation does).
        val oldKey: suspend () -> Unit = { d.cloudSecrets.ring(g).put(1, ByteArray(32) { 7 }) }
        val held = GatedDisk(disk) {
            entered.complete(Unit)
            release.await()
            oldKey()
        }
        var queuedTouchedTheDisk = false
        val queued = GatedDisk(disk) {
            queuedTouchedTheDisk = true
            oldKey()
        }
        // Run 1 is inside CloudSync when the wipe starts; run 2 starts after the wipe queued behind run 1.
        val run1 = async(Dispatchers.Default) { runCatching { d.runner(held).run(CloudTrigger.NOW) } }
        withTimeout(20_000) { entered.await() }
        val wipe = async(Dispatchers.Default) { d.reset.forgetGroup() }
        delay(300)
        assertTrue(d.cloudSecrets.runLock.isLocked)
        assertFalse(wipe.isCompleted, "the wipe waits for the run")
        assertTrue(d.groups.exists(), "nothing is wiped while a run holds the lock")
        val run2 = async(Dispatchers.Default) { d.runner(queued).run(CloudTrigger.NOW) }
        delay(300)
        release.complete(Unit)
        withTimeout(20_000) {
            run1.await()
            wipe.await()
        }
        assertEquals(CloudRun.OFF, withTimeout(20_000) { run2.await() }, "the queued run finds no group")
        assertFalse(queuedTouchedTheDisk)
        assertFalse(File(d.dir, GroupKeyFile.FILE_NAME).exists(), "nothing of the old group is written back")
        assertFalse(File(d.dir, CloudTokenVault.FILE_NAME).exists())
        assertNull(d.cloudSecrets.tokens())
    }

    /** Runs [gate] once, on the first Disk call. */
    private class GatedDisk(private val inner: DiskApi, private val gate: suspend () -> Unit) : DiskApi {
        private var gated = false

        private suspend fun once() {
            if (!gated) {
                gated = true
                gate()
            }
        }

        override suspend fun list(path: String): List<DiskItem> = once().let { inner.list(path) }

        override suspend fun mkdir(path: String) = once().let { inner.mkdir(path) }

        override suspend fun upload(path: String, bytes: ByteArray) = once().let { inner.upload(path, bytes) }

        override suspend fun download(path: String): ByteArray = once().let { inner.download(path) }

        override suspend fun delete(path: String) = once().let { inner.delete(path) }
    }
}
