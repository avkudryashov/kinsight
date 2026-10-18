package app.kinsight.core.transport.cloud

import app.kinsight.core.crypto.Primitives
import app.kinsight.core.model.DeviceKeys
import app.kinsight.core.model.EntryAuthor
import app.kinsight.core.model.GroupGenesis
import app.kinsight.core.model.Membership
import app.kinsight.core.model.addMember
import app.kinsight.core.model.proto.DeviceRole
import app.kinsight.core.model.proto.PackFile
import app.kinsight.core.model.proto.PackHeader
import app.kinsight.core.sync.summary
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PackCodecTest {
    private val momKeys = DeviceKeys.generate()
    private val genesis = GroupGenesis.create(momKeys, "Mom")
    private val mom = EntryAuthor(genesis.groupId, momKeys, lastSeq = 1)
    private val annaKeys = DeviceKeys.generate()
    private val anna = EntryAuthor(genesis.groupId, annaKeys, lastSeq = 0)
    private val bobKeys = DeviceKeys.generate()
    private val membership = Membership.compute(
        genesis.groupId,
        listOf(
            genesis.entry,
            mom.addMember(annaKeys.sign.publicKey, annaKeys.dh.publicKey, "Anna", DeviceRole.CHILD),
            mom.addMember(bobKeys.sign.publicKey, bobKeys.dh.publicKey, "Bob", DeviceRole.CHILD),
        ),
    )!!
    private val gen0 = Primitives.randomBytes(32)
    private val ring = mapOf(0 to gen0)
    private val e1 = anna.summary("2026-10-12", 60_000)
    private val e2 = anna.summary("2026-10-13", 30_000)

    private fun annaPack(epoch: Int = 0, key: ByteArray = gen0) =
        PackCodec.seal(annaKeys, genesis.groupId, listOf(e1, e2), 1, 2, epoch, key, 5)

    /** Anna's pack with its header range changed and re-signed by Anna: only the range check can reject it. */
    private fun withRange(from: Long, to: Long): ByteArray {
        val f = PackFile.ADAPTER.decode(annaPack())
        val header = PackHeader.ADAPTER.encode(PackHeader.ADAPTER.decode(f.header_).copy(from_seq = from, to_seq = to))
        val signature = annaKeys.sign.sign("kinsight-pack-v1".encodeToByteArray() + header)
        return PackFile.ADAPTER.encode(f.copy(header_ = header.toByteString(), signature = signature.toByteString()))
    }

    @Test
    fun roundTrip() {
        val opened = assertIs<PackOutcome.Opened>(PackCodec.open(annaPack(), ring, genesis.groupId, annaKeys.deviceId, membership)).pack
        assertEquals(annaKeys.deviceId, opened.author)
        assertEquals(Triple(1L, 2L, 0), Triple(opened.from, opened.to, opened.epoch))
        assertContentEquals(e2.bytes, opened.entries[1])
        assertEquals(0, PackCodec.epochOf(annaPack()))
    }

    @Test
    fun keyUpdatesTravelInClear() {
        val rotation = GroupKey.rotate(mom, membership)
        val pack = PackCodec.seal(annaKeys, genesis.groupId, listOf(e1), 1, 1, 1, Primitives.randomBytes(32), 5, keyUpdates = listOf(rotation))
        assertEquals(listOf(rotation), PackCodec.keyUpdates(pack))
        assertEquals(emptyList(), PackCodec.keyUpdates(annaPack()))
    }

    @Test
    fun atMostFourKeyUpdatesAreReadFromAPack() {
        val rotations = List(6) { GroupKey.rotate(mom, membership) }
        val pack = PackCodec.seal(annaKeys, genesis.groupId, listOf(e1), 1, 1, 1, Primitives.randomBytes(32), 5, keyUpdates = rotations)
        assertEquals(rotations.take(PackCodec.MAX_KEY_UPDATES), PackCodec.keyUpdates(pack))
        assertEquals(4, PackCodec.MAX_KEY_UPDATES)
    }

    @Test
    fun headerRangeDifferentFromTheFileNameIsRejected() {
        assertIs<PackOutcome.Opened>(PackCodec.open(annaPack(), ring, genesis.groupId, annaKeys.deviceId, membership, expected = 1L..2L))
        val renamed = assertIs<PackOutcome.Rejected>(PackCodec.open(annaPack(), ring, genesis.groupId, annaKeys.deviceId, membership, expected = 1L..9L))
        assertEquals("range differs from the file name", renamed.reason)
    }

    @Test
    fun aGenerationNotInTheRingIsReported() {
        val pack = annaPack(epoch = 1, key = Primitives.randomBytes(32))
        assertEquals(1, assertIs<PackOutcome.KeyUnknown>(PackCodec.open(pack, ring, genesis.groupId, annaKeys.deviceId, membership)).epoch)
    }

    @Test
    fun wrongKeyForTheGenerationIsAKeyMismatch() {
        val pack = annaPack(epoch = 0, key = Primitives.randomBytes(32))
        assertEquals(0, assertIs<PackOutcome.KeyMismatch>(PackCodec.open(pack, ring, genesis.groupId, annaKeys.deviceId, membership)).epoch)
    }

    @Test
    fun packInAnotherDevicesFolderIsRejected() {
        assertIs<PackOutcome.Rejected>(PackCodec.open(annaPack(), ring, genesis.groupId, bobKeys.deviceId, membership))
    }

    @Test
    fun tamperedCiphertextDoesNotOpen() {
        val file = PackFile.ADAPTER.decode(annaPack())
        val ct = file.ciphertext.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = PackFile.ADAPTER.encode(file.copy(ciphertext = ct.toByteString()))
        // Indistinguishable from another key of the same generation: not final, retried when the ring's key changes.
        assertIs<PackOutcome.KeyMismatch>(PackCodec.open(bad, ring, genesis.groupId, annaKeys.deviceId, membership))
    }

    @Test
    fun headerSignedByAnotherMemberIsRejected() {
        val genuine = PackFile.ADAPTER.decode(annaPack())
        val forged = PackFile.ADAPTER.decode(PackCodec.seal(bobKeys, genesis.groupId, emptyList(), 1, 2, 0, gen0, 5))
        val swapped = PackFile.ADAPTER.encode(genuine.copy(signature = forged.signature))
        assertIs<PackOutcome.Rejected>(PackCodec.open(swapped, ring, genesis.groupId, annaKeys.deviceId, membership))
    }

    @Test
    fun unknownAuthorIsReportedNotRejected() {
        val stranger = DeviceKeys.generate()
        val pack = PackCodec.seal(stranger, genesis.groupId, emptyList(), 1, 1, 0, gen0, 5)
        assertIs<PackOutcome.UnknownAuthor>(PackCodec.open(pack, ring, genesis.groupId, stranger.deviceId, membership))
    }

    @Test
    fun badRangeForeignGroupAndGarbageAreRejected() {
        assertIs<PackOutcome.Rejected>(PackCodec.open(withRange(3, 2), ring, genesis.groupId, annaKeys.deviceId, membership))
        val other = GroupGenesis.create(DeviceKeys.generate(), "Other").groupId
        assertIs<PackOutcome.Rejected>(PackCodec.open(annaPack(), ring, other, annaKeys.deviceId, membership))
        assertIs<PackOutcome.Rejected>(PackCodec.open(byteArrayOf(1, 2, 3), ring, genesis.groupId, annaKeys.deviceId, membership))
    }
}
