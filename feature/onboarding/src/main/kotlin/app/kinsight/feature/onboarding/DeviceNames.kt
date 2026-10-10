package app.kinsight.feature.onboarding

import app.kinsight.core.model.MembershipLimits

/**
 * The name this phone shows to the family. core/sync's PairingNames is internal and only covers controls and bidi
 * marks, so the onboarding check is stricter: no blank name, no C0/C1 control, no Unicode format character (bidi
 * embeddings, overrides, isolates and marks, zero-width spaces, BOM, soft hyphen) except the ZWJ that joins emoji,
 * no line or paragraph separator, and within [MembershipLimits.nameOk].
 */
object DeviceNames {
    private const val ZWJ = 0x200D

    fun unsafe(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        Character.FORMAT -> cp != ZWJ
        else -> false
    }

    fun ok(name: String): Boolean =
        name.isNotBlank() && MembershipLimits.nameOk(name) && name.codePoints().noneMatch(::unsafe)
}
