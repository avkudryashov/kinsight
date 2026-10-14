package app.kinsight.core.model

/**
 * The single source of size and clock limits. Used at creation (GroupGenesis, [addMember]), by
 * Membership.relevant (over-cap ops are dropped identically on every replica, the author's included) and by the
 * sync validator on receipt, so no replica can see an op another replica rejects.
 */
object MembershipLimits {
    const val MAX_ENTRY_BYTES = 8 * 1024
    const val MAX_NAME_CODE_POINTS = 64
    const val MAX_NAME_BYTES = 256

    /** Most departed ids one StorageOwner op lists (spec §7.4); extra ids are ignored when reading. */
    const val MAX_OWNER_DEPARTED = 64

    /** Largest valid Lamport value; anything ≥ 2^62 (or ≥ 2^63 as uint64, read as negative) is invalid. */
    const val MAX_LAMPORT = (1L shl 62) - 1

    fun nameOk(name: String): Boolean =
        name.codePointCount(0, name.length) <= MAX_NAME_CODE_POINTS && name.encodeToByteArray().size <= MAX_NAME_BYTES

    fun lamportOk(lamport: Long): Boolean = lamport in 0..MAX_LAMPORT

    /** Lamport cap for every entry; for membership-relevant entries also encoded size and display-name caps. */
    fun withinLimits(e: DecodedEntry): Boolean {
        if (!lamportOk(e.lamport)) return false
        if (!e.isMembershipRelevant) return true
        if (e.bytes.size > MAX_ENTRY_BYTES) return false
        val op = e.body.membership_op ?: return true
        val name = op.create?.display_name ?: op.add?.display_name ?: return true
        return nameOk(name)
    }
}
