package app.kinsight.core.model

import app.kinsight.core.crypto.Ed25519
import app.kinsight.core.model.proto.EntryBody
import app.kinsight.core.model.proto.SignedEntry
import app.kinsight.core.model.proto.StateEvent
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class EntryKind { USAGE_SUMMARY, USAGE_DETAIL, APP_CATALOG, STATE_EVENT, HEARTBEAT, MEMBERSHIP_OP, UNKNOWN }

class EntryFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DecodedEntry private constructor(
    val bytes: ByteArray,
    val body: EntryBody,
    private val bodyBytes: ByteArray,
    private val signature: ByteArray,
) {
    val groupId: GroupId = GroupId.fromBytes(body.group_id.toByteArray())
    val author: DeviceId = DeviceId.fromBytes(body.author_id.toByteArray())
    val seq: Long get() = body.seq
    val lamport: Long get() = body.lamport

    val kind: EntryKind = when {
        body.usage_summary != null -> EntryKind.USAGE_SUMMARY
        body.usage_detail != null -> EntryKind.USAGE_DETAIL
        body.app_catalog != null -> EntryKind.APP_CATALOG
        body.state_event != null -> EntryKind.STATE_EVENT
        body.heartbeat != null -> EntryKind.HEARTBEAT
        body.membership_op != null -> EntryKind.MEMBERSHIP_OP
        else -> EntryKind.UNKNOWN
    }

    val supersedeKey: String? = when (kind) {
        EntryKind.USAGE_SUMMARY -> "${author.hex}/sum/${body.usage_summary!!.local_date}"
        EntryKind.USAGE_DETAIL -> "${author.hex}/det/${body.usage_detail!!.local_date}"
        EntryKind.APP_CATALOG -> "${author.hex}/app/${body.app_catalog!!.package_name}"
        EntryKind.HEARTBEAT -> "${author.hex}/hb"
        else -> null
    }

    val isMembershipRelevant: Boolean =
        kind == EntryKind.MEMBERSHIP_OP ||
            (kind == EntryKind.STATE_EVENT && body.state_event!!.kind == StateEvent.Kind.LEFT_GROUP)

    val usageDate: LocalDate? = (body.usage_summary?.local_date ?: body.usage_detail?.local_date)?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun verifySignature(signPublicKey: ByteArray): Boolean = Ed25519.verify(signPublicKey, bodyBytes, signature)

    override fun equals(other: Any?) = other is DecodedEntry && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Entry(${author.hex.take(8)}#$seq $kind)"

    companion object {
        fun decode(bytes: ByteArray): DecodedEntry {
            val signed = try {
                SignedEntry.ADAPTER.decode(bytes)
            } catch (e: Exception) {
                throw EntryFormatException("bad envelope", e)
            }
            val bodyBytes = signed.body.toByteArray()
            val body = try {
                EntryBody.ADAPTER.decode(bodyBytes)
            } catch (e: Exception) {
                throw EntryFormatException("bad body", e)
            }
            if (body.group_id.size != 16 || body.author_id.size != 16 || signed.signature.size != 64) {
                throw EntryFormatException("bad field length")
            }
            return DecodedEntry(bytes.copyOf(), body, bodyBytes, signed.signature.toByteArray())
        }
    }
}
