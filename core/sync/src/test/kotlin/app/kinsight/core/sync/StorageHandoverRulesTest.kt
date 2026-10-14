package app.kinsight.core.sync

import app.kinsight.core.model.DecodedEntry
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.EntryKind
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.StorageHandover
import app.kinsight.core.model.storageHandoverEntry
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StorageHandoverRulesTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val kidKeys = DeviceKeys.generate()
    private val kid = EntryAuthor(genesis.groupId, kidKeys, lastSeq = 0)
    private val dadKeys = DeviceKeys.generate()
    private val addKid = mom.addMember(kidKeys.sign.publicKey, kidKeys.dh.publicKey, "Kid", DeviceRole.CHILD)
    private val addDad = mom.addMember(dadKeys.sign.publicKey, dadKeys.dh.publicKey, "Dad", DeviceRole.PARENT)
    private val state = Membership.compute(genesis.groupId, listOf(genesis.entry, addKid, addDad))!!

    private fun verdict(e: DecodedEntry, self: DeviceKeys = momKeys, showOthers: Boolean = true) =
        EntryValidator(genesis.groupId, state, SyncPolicy(self.deviceId, showOthers, today = { TODAY })).check(e, alreadyAccounted = false)

    @Test
    fun aParentsHandoverIsAcceptedAndKeptOncePerAuthor() {
        val e = mom.storageHandoverEntry(dadKeys.deviceId, 1)
        assertEquals(EntryKind.STORAGE_HANDOVER, e.kind)
        assertEquals("${momKeys.deviceId.hex}/handover", e.supersedeKey)
        assertEquals(Verdict.Accept, verdict(e))
    }

    @Test
    fun aChildsHandoverIsSkipped() = assertIs<Verdict.Skipped>(verdict(kid.storageHandoverEntry(dadKeys.deviceId, 1)))

    @Test
    fun aMalformedHandoverIsSkipped() {
        assertIs<Verdict.Skipped>(verdict(mom.storageHandoverEntry(dadKeys.deviceId, 0)))
        val shortId = mom.create { it.copy(storage_handover = StorageHandover(to_device_id = ByteArray(15).toByteString(), generation = 1)) }
        assertIs<Verdict.Skipped>(verdict(shortId))
    }

    @Test
    fun aChildDoesNotTakeAHandover() =
        assertEquals(Verdict.Unwanted, verdict(mom.storageHandoverEntry(dadKeys.deviceId, 1), self = kidKeys, showOthers = false))

    @Test
    fun aHandoverOverTheBodyCapIsSkipped() {
        val big = mom.create { it.copy(storage_handover = StorageHandover(to_device_id = ByteArray(2000).toByteString(), generation = 1)) }
        assertTrue(StorageHandoverRules.violation(big, state)!!.contains("over ${StorageHandoverRules.MAX_BODY_BYTES} bytes"))
        assertIs<Verdict.Skipped>(verdict(big))
    }
}
