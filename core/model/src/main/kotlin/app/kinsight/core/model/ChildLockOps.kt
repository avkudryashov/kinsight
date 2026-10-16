package app.kinsight.core.model

import app.kinsight.core.model.proto.ParentPin
import okio.ByteString.Companion.toByteString

/** The parents' PIN as a slow hash (spec §10); [rank] orders it after the PIN it replaces (core/data ParentPins.nextRank). */
fun EntryAuthor.parentPinEntry(salt: ByteArray, hash: ByteArray, memoryKib: Int, iterations: Int, rank: Long): DecodedEntry = create {
    it.copy(parent_pin = ParentPin(salt = salt.toByteString(), hash = hash.toByteString(), memory_kib = memoryKib, iterations = iterations, rank = rank))
}
