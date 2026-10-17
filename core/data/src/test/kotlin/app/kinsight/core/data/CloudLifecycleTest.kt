package app.kinsight.core.data

import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.Membership
import app.kinsight.core.model.leaveGroupEntry
import app.kinsight.core.transport.cloud.CloudAccessView
import app.kinsight.core.transport.cloud.GroupKey
import app.kinsight.core.transport.cloud.InMemoryDisk
import app.kinsight.core.transport.cloud.OwnerLifecycle
import app.kinsight.core.transport.cloud.TokenSet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudLifecycleTest {
    private val mom = TestDevice()
    private val dad = TestDevice()
    private val kid = TestDevice()
    private val disk = InMemoryDisk()
    private val http = FakeOAuthHttp(accessToken = "AT2")
    private val disks = AccountDisks(mapOf("AT" to disk, "AT2" to disk))

    @AfterTest
    fun cleanUp() {
        mom.close()
        dad.close()
        kid.close()
    }

    private suspend fun family() {
        mom.group.createGroup("Mom")
        dad.joinAsAdminOf(mom)
        kid.joinGroupOf(mom)
    }

    private suspend fun membershipOf(d: TestDevice) = Membership.compute(d.groups.read()!!.groupId, d.store().membershipEntries())!!

    private fun revokes() = http.urls.count { it.endsWith("/revoke_token") }

    private fun refreshes() = http.forms.count { it["grant_type"] == "refresh_token" }

    @Test
    fun theOwnerRefreshesWhenLessThanAMonthIsLeft() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT", expiresAtMs = mom.now + 20 * DAY)
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http).run(CloudTrigger.NOW))
        val form = http.forms.single()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("RT", form["refresh_token"])
        assertEquals(TEST_CLOUD.clientSecret, form["client_secret"])
        val t = mom.cloudSecrets.tokens()!!
        assertEquals("AT2" to "RT", t.accessToken to t.refreshToken)
        assertTrue(t.owner)
        val g = mom.groups.read()!!
        val shared = CloudAccessView.current(mom.store(), membershipOf(mom), mom.cloudSecrets.ring(g).keys())!!
        assertEquals("AT2", shared.accessToken, "«новый доступ» for the others (spec §6)")
        mom.now += DAY
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, http.forms.size, "checked once a week")
    }

    /** Task 10 review Minor 4: no refresh while the owner's transfer invite may still hand its refresh token (spec §7.3). */
    @Test
    fun theOwnerDoesNotRefreshWhileATransferIsPending() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT", expiresAtMs = mom.now + 20 * DAY)
        val g = mom.groups.read()!!
        mom.cloudState.recordTransfer(OwnerTransfers.record(g.groupId, dad.keys.deviceId, membershipOf(mom).storageOwner!!, mom.now + 600_000))
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals(0, refreshes())
        mom.now += 600_001 // the record expired with the invite
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, refreshes())
    }

    /** Rulings Q9: weekly checks; a refresh at 30 days of age even with most of the year left. */
    @Test
    fun aTokenIsLeftAloneUnderAMonthOldAndRefreshedAtAMonth() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT", expiresAtMs = mom.now + YEAR)
        val runner = mom.runner(disks::disk, http)
        runner.run(CloudTrigger.NOW)
        assertEquals(0, refreshes())
        assertEquals(mom.now, mom.cloudState.current().refreshCheckMs)
        assertEquals("AT", mom.cloudSecrets.tokens()!!.accessToken)
        mom.now += 22 * DAY
        runner.run(CloudTrigger.NOW)
        assertEquals(0, refreshes(), "22 days old, most of the year left")
        assertEquals(mom.now, mom.cloudState.current().refreshCheckMs)
        mom.now += 8 * DAY
        runner.run(CloudTrigger.NOW)
        assertEquals(1, refreshes(), "30 days old")
        assertEquals("AT2", mom.cloudSecrets.tokens()!!.accessToken)
        mom.now += 22 * DAY
        runner.run(CloudTrigger.NOW)
        assertEquals(1, refreshes(), "the age counts from the refresh")
    }

    /** Rulings Q8: a refresh after this run's rotation is sealed under the new key, which the departed kid lacks. */
    @Test
    fun aRefreshAfterADepartureIsSealedUnderTheRotatedKey() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT", expiresAtMs = mom.now + 20 * DAY)
        check(kid.appendOwn { a -> listOf(a.leaveGroupEntry()) })
        mom.store().storeMembership(kid.store().membershipEntries())
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, refreshes())
        val m = membershipOf(mom)
        assertEquals(1, m.discoveryRotation!!.epoch)
        val newest = mom.store().entriesAfter(mom.keys.deviceId, 0, 1000).last { it.kind == EntryKind.CLOUD_ACCESS }
        assertEquals(1, newest.body.cloud_access!!.key_epoch)
        assertEquals("AT2", CloudAccessView.current(m, listOf(newest), mom.cloudSecrets.ring(mom.groups.read()!!).keys())!!.accessToken)
    }

    @Test
    fun theOldTokenIsRevokedOnlyAfterEveryoneSwitched() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        val g = mom.groups.read()!!
        val storage = ByteArray(16) { 1 }
        // What the sign-in after a removal leaves (Task 8): generation 2 with a new token, the old one retired.
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), storage, 2, "AT2", mom.now + YEAR))
        mom.cloudSecrets.saveOwner(g.groupId, 2, storage, TokenSet("AT2", "RT", mom.now + YEAR))
        mom.cloudSecrets.retire(g.groupId, 1, "AT")
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(0, revokes(), "Dad and the kid have not switched yet")
        mom.store().applyBatch(listOf(dad.beat(2)))
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(0, revokes(), "the kid still has not")
        mom.store().applyBatch(listOf(kid.beat(2)))
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, revokes())
        assertEquals("AT", http.forms.last()["access_token"])
        assertEquals(TEST_CLOUD.clientSecret, http.forms.last()["client_secret"])
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty())
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, revokes(), "revoked once")
    }

    /** Final review, Important 1: a kid's phone on a build older than the cloud never uses a token and is not waited for. */
    @Test
    fun aPhoneOnAnOldBuildDoesNotHoldUpTheRevoke() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        val g = mom.groups.read()!!
        val storage = ByteArray(16) { 1 }
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), storage, 2, "AT2", mom.now + YEAR))
        mom.cloudSecrets.saveOwner(g.groupId, 2, storage, TokenSet("AT2", "RT", mom.now + YEAR))
        mom.cloudSecrets.retire(g.groupId, 1, "AT")
        mom.store().applyBatch(listOf(dad.beat(2), kid.beat(0, protocol = 2)))
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertEquals(1, revokes())
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty())
    }

    /** Rulings Q3: the claim names a fresh token and every departure its signer knows of. */
    @Test
    fun aClaimNamesAFreshTokenAndTheKnownDepartures() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        check(kid.appendOwn { a -> listOf(a.leaveGroupEntry()) })
        mom.store().storeMembership(kid.store().membershipEntries())
        val g = mom.groups.read()!!
        val claimed = OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), ByteArray(16) { 1 }, 2, "AT2", mom.now + YEAR)!!
        val owner = membershipOf(mom).storageOwner!!
        assertEquals(2L, owner.generation)
        assertEquals(16, owner.tokenId.size)
        assertEquals(setOf(kid.keys.deviceId), owner.departed)
        assertEquals(2, claimed.entries.size)
        val same = ByteArray(16) { 9 }
        OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), ByteArray(16) { 1 }, 3, "AT2", mom.now + YEAR, tokenId = same)!!
        assertEquals(same.toByteString(), membershipOf(mom).storageOwner!!.tokenId, "the same token goes on (spec §7.3)")
        assertNull(
            OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), ByteArray(16) { 1 }, 3, "AT2", mom.now + YEAR),
            "not above the current generation",
        )
    }

    /** Rulings Q10: everyone reports the new generation, so only "still in use" keeps the token from the revoke. */
    @Test
    fun aRetiredTokenStillInUseIsDroppedWithoutARevoke() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom)
        val g = dad.groups.read()!!
        dad.store().applyBatch(listOf(mom.beat(2), kid.beat(2)))
        assertTrue(OwnerLifecycle.allSwitched(membershipOf(dad), dad.store().heartbeats(), 1, dad.keys.deviceId))
        dad.cloudSecrets.retire(g.groupId, 1, "AT") // spec §7.3: the QR transfer goes on with the same token
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals(0, revokes())
        assertTrue(dad.cloudSecrets.retired(g.groupId).isEmpty())
    }

    @Test
    fun theLosingOwnerIssuesItsAccessAgainUnderTheWinningKey() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        val g = mom.groups.read()!!
        // Mom rotated to generation 1 with her key and sealed her access with it; Dad's rotation of generation 1 won.
        val momsKey = ByteArray(32) { 5 }
        mom.cloudSecrets.ring(g).put(1, momsKey)
        val before = membershipOf(mom)
        check(mom.appendOwn { a -> listOf(CloudAccessView.issue(a, before, 1, momsKey, "AT", mom.now + YEAR)) })
        dad.store().storeMembership(mom.store().membershipEntries())
        val atDad = membershipOf(dad)
        check(dad.appendOwn { a -> listOf(GroupKey.rotate(a, atDad)) })
        mom.store().storeMembership(dad.store().membershipEntries())
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http).run(CloudTrigger.NOW))
        val m = membershipOf(mom)
        val winning = GroupKey.open(mom.keys, m)!!
        assertContentEquals(winning.second, mom.cloudSecrets.ring(g).keys()[1])
        assertEquals("AT", CloudAccessView.current(mom.store(), m, mapOf(winning))!!.accessToken, "readable under the key that won")
    }

    @Test
    fun aDepartureMakesTheOwnerRotateAndNoOtherAdmin() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom)
        check(kid.appendOwn { a -> listOf(a.leaveGroupEntry()) })
        for (d in listOf(mom, dad)) d.store().storeMembership(kid.store().membershipEntries())
        dad.runner(disks::disk, http).run(CloudTrigger.NOW)
        assertNull(membershipOf(dad).discoveryRotation, "the owner is around: Dad leaves it to her")
        mom.store().storeMembership(dad.store().membershipEntries())
        mom.runner(disks::disk, http).run(CloudTrigger.NOW)
        val r = membershipOf(mom).discoveryRotation!!
        assertEquals(1, r.epoch)
        assertFalse(r.keys.any { it.device_id == kid.keys.deviceId.bytes().toByteString() })
    }

    @Test
    fun aRunOnANewGenerationSignsAHeartbeatOnce() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        var beats = 0
        val runner = mom.runner(disks::disk, http, afterSync = { beats++ })
        runner.run(CloudTrigger.NOW)
        runner.run(CloudTrigger.NOW)
        assertEquals(1, beats)
        assertEquals(1L, mom.cloudState.current().syncedGeneration)
    }

    /** Rulings Q18: a member that only meets the cloud confirms the switch to a new generation in its Heartbeat. */
    @Test
    fun aCloudOnlyMemberConfirmsTheGenerationItSyncsWith() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom)
        var generation = -1L
        val runner = dad.runner(disks::disk, http, afterSync = { generation = dad.cloudState.current().syncedGeneration!! })
        assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW))
        assertEquals(1L, generation, "recorded before the Heartbeat is signed")
    }

    /** Rulings Q18: a Bluetooth session's note waits for a wipe in progress and then finds no group: nothing stale written. */
    @Test
    fun theOwnerNoteAfterAWipeWritesNothing() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom)
        val runner = dad.runner(disks::disk, http)
        dad.cloudSecrets.runLock.lock() // the wipe holds the run lock across the group files and cloudState.clear()
        val note = async(start = CoroutineStart.UNDISPATCHED) { runner.noteOwnerHeard() }
        delay(50)
        assertFalse(note.isCompleted, "waits for the wipe")
        dad.groups.delete()
        dad.cloudState.clear()
        dad.cloudSecrets.runLock.unlock()
        note.await()
        assertNull(dad.cloudState.current().ownerHeard)
    }

    /**
     * Fix round 1, Important 1: a §7.3 transfer keeps token AT (same token_id); the new owner refreshes to AT2 at once.
     * Mom retires AT with her old owner role, everyone reports generation 2, and still AT is never revoked: the phones
     * that switched before the refresh use it.
     */
    @Test
    fun aTokenTheNewOwnerKeptIsNeverRevoked() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        val storage = ByteArray(16) { 1 }
        val tokenId = ByteArray(16) { 4 }
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), storage, 1, "AT", mom.now + 20 * DAY, tokenId))
        mom.cloudSecrets.saveOwner(g.groupId, 1, storage, TokenSet("AT", "RT", mom.now + 20 * DAY))
        dad.store().storeMembership(mom.store().membershipEntries())
        assertNotNull(OwnerRecords.claim(dad.appendOwn, dad.store(), dad.keys, dad.cloudSecrets.ring(g), storage, 2, "AT", dad.now + 20 * DAY, tokenId))
        dad.cloudSecrets.saveOwner(g.groupId, 2, storage, TokenSet("AT", "RT", dad.now + 20 * DAY))
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals(1, refreshes(), "fewer than 30 days left: Dad refreshes at once")
        dad.beat(2)
        mom.store().storeMembership(dad.store().membershipEntries())
        mom.store().applyBatch(dad.store().entriesAfter(dad.keys.deviceId, 0, 1000) + kid.beat(2))
        assertTrue(OwnerLifecycle.allSwitched(membershipOf(mom), mom.store().heartbeats(), 1, mom.keys.deviceId))
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals(0, revokes())
        assertTrue(mom.cloudSecrets.retired(g.groupId).isEmpty(), "dropped, not kept for a revoke")
    }

    /** Fix round 1, Important 2 (§7.2): the owner's phone is lost, never removed; after a week unheard Dad rotates for the kid. */
    @Test
    fun aLostOwnerPhoneLetsAnotherAdminRotateForADeparture() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        dad.learnsStorageFrom(mom)
        check(kid.appendOwn { a -> listOf(a.leaveGroupEntry()) })
        dad.store().storeMembership(kid.store().membershipEntries())
        val runner = dad.runner(disks::disk, http)
        runner.run(CloudTrigger.NOW)
        assertNull(membershipOf(dad).discoveryRotation, "Mom was just heard here: Dad leaves it to her")
        dad.now += 7 * DAY - 1
        runner.run(CloudTrigger.NOW)
        assertNull(membershipOf(dad).discoveryRotation, "not a full week yet")
        dad.now += 1
        runner.run(CloudTrigger.NOW)
        val m = membershipOf(dad)
        assertTrue(mom.keys.deviceId in m.active, "never removed")
        val r = m.discoveryRotation!!
        assertEquals(1, r.epoch)
        assertFalse(r.keys.any { it.device_id == kid.keys.deviceId.bytes().toByteString() })
    }

    /** Fix round 1, Minor 3: a Heartbeat that failed after a new generation is tried again at the next synced run. */
    @Test
    fun aFailedHeartbeatIsTriedAgainAtTheNextSync() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        var calls = 0
        val runner = mom.runner(disks::disk, http, afterSync = { if (++calls == 1) error("no Heartbeat") })
        repeat(3) { assertEquals(CloudRun.SYNCED, runner.run(CloudTrigger.NOW)) }
        assertEquals(2, calls)
    }

    /**
     * Y3 parked concern: a parent whose only access is the owner's CloudAccess in the log, sealed under a key generation
     * that came to it over Bluetooth (the rotation op) but never reached its ring (absorbing happened only inside a sync),
     * takes that key before it looks for its access, instead of NO_ACCESS until the owner hands its token.
     */
    @Test
    fun aParentOpensTheLogsAccessUnderAKeyItHasNotAbsorbedYet() = runBlocking<Unit> {
        family()
        val g = mom.groups.read()!!
        check(mom.appendOwn { a -> listOf(GroupKey.rotate(a, membershipOf(mom))) })
        assertNotNull(OwnerRecords.claim(mom.appendOwn, mom.store(), mom.keys, mom.cloudSecrets.ring(g), ByteArray(16) { 1 }, 1, "AT", mom.now + YEAR))
        mom.cloudSecrets.saveOwner(g.groupId, 1, ByteArray(16) { 1 }, TokenSet("AT", "RT", mom.now + YEAR))
        // Bluetooth: Dad gets Mom's log (the rotation, StorageOwner, CloudAccess), no token in his vault.
        dad.store().storeMembership(mom.store().membershipEntries())
        dad.store().applyBatch(mom.store().entriesAfter(mom.keys.deviceId, 0, 10_000))
        assertNull(dad.cloudSecrets.tokens())
        assertEquals(setOf(0), dad.cloudSecrets.ring(g).keys().keys, "generation 1 is only in the log")
        assertEquals(CloudRun.SYNCED, dad.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertNull(dad.cloudState.current().problem)
    }

    /** Parked Y3 minor: the Heartbeat still owed survives the process (a new runner reads it from the cloud state). */
    @Test
    fun aHeartbeatStillOwedSurvivesProcessDeath() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        var calls = 0
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http, afterSync = { calls++; error("no Heartbeat") }).run(CloudTrigger.NOW))
        assertTrue(mom.cloudState.current().heartbeatPending)
        val restarted = mom.runner(disks::disk, http, afterSync = { calls++ })
        assertEquals(CloudRun.SYNCED, restarted.run(CloudTrigger.NOW))
        assertEquals(2, calls, "tried again after the restart")
        assertFalse(mom.cloudState.current().heartbeatPending)
        restarted.run(CloudTrigger.NOW)
        assertEquals(2, calls, "and only once")
    }

    /** Fix round 1, Minor 2: a refreshed token whose CloudAccess is missing goes out at the next synced run. */
    @Test
    fun aMissingAccessForTheVaultTokenIsIssuedAgain() = runBlocking<Unit> {
        family()
        mom.ownStorage(token = "AT")
        val g = mom.groups.read()!!
        mom.cloudSecrets.saveOwner(g.groupId, 1, ByteArray(16) { 1 }, TokenSet("AT2", "RT", mom.now + YEAR)) // as if the append failed
        assertEquals(CloudRun.SYNCED, mom.runner(disks::disk, http).run(CloudTrigger.NOW))
        assertEquals("AT2", CloudAccessView.current(mom.store(), membershipOf(mom), mom.cloudSecrets.ring(g).keys())!!.accessToken)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val YEAR = 365 * DAY
    }
}
